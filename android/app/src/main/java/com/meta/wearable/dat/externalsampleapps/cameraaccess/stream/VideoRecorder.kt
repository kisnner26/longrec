/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VideoRecorder - Streaming Video + Audio Recording Orchestrator
//
// Streams compressed HEVC frames from the DAT SDK directly to an MP4 file in the cache
// directory via VideoCaptureHandler — no video re-encoding. Phone-mic audio is encoded
// incrementally to AAC and interleaved when available; a device with no usable mic
// records video-only. The track opens on the first detectable keyframe (falling back to
// any frame so recording always starts). The finished file is exposed as a FileProvider
// Uri for preview/share and is deleted by the caller once previewed.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of finalizing a recording. */
sealed interface RecordingResult {
  /** A playable file was written and is exposed at [uri] (caller deletes it after previewing). */
  data class Completed(val uri: Uri) : RecordingResult

  /** Nothing was recorded — e.g. stopped before the first keyframe arrived. */
  data object NoRecording : RecordingResult

  /** A file was started but could not be finalized. */
  data object Failed : RecordingResult
}

class VideoRecorder(
    context: Context,
    // The owner's scope (e.g. viewModelScope) — drives the elapsed timer and audio watchdog so they
    // are cancelled with the owner. close() cancels its own jobs but never this shared scope.
    private val scope: CoroutineScope,
) {

  // Stored as the application context so this non-lifecycle class never retains an Activity.
  private val context: Context = context.applicationContext

  companion object {
    private const val TAG = "VideoRecorder"
    // How long to wait for phone-mic audio to start flowing after the first keyframe before falling
    // back to video-only. Real-device audio produces a format well within this; a silent/absent mic
    // (e.g. an emulator) never does, and without the fallback the muxer would wait forever.
    private const val AUDIO_READY_TIMEOUT_MS = 1000L
    // Each segment is its own .mp4, rotated on a keyframe once it reaches this length, so a crash
    // or a dropped session only loses the current segment.
    private const val SEGMENT_SECONDS = 5 * 60L
  }

  private val videoCaptureHandler = VideoCaptureHandler()

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  private val _recordingElapsedSeconds = MutableStateFlow(0L)
  val recordingElapsedSeconds: StateFlow<Long> = _recordingElapsedSeconds.asStateFlow()

  // True once the first keyframe has been written and the file is actually capturing. Lets the
  // caller wait briefly on stop so a clip taken right before a keyframe still finalizes.
  private val _hasStartedWriting = MutableStateFlow(false)
  val hasStartedWriting: StateFlow<Boolean> = _hasStartedWriting.asStateFlow()

  private var audioInputHandler: AudioInputHandler? = null
  private var includeAudio: Boolean = true

  // Called with each segment finished mid-recording (rotation). The last segment is returned by
  // stopRecording(). The owner saves and deletes the file the Uri points to.
  @Volatile var onSegmentFinished: ((Uri) -> Unit)? = null

  // Segments started in the current recording (1 = a short recording with no rotation).
  @Volatile var segmentCount: Int = 0
    private set

  @Volatile private var rotating = false
  @Volatile private var segmentSeconds = 0L
  @Volatile private var lastCodecConfig: ByteArray? = null

  // @Volatile: published across the caller, frame-delivery, and stop threads — timerJob and
  // audioWatchdogJob are assigned on the frame thread (writeCompressedFrame) and cancelled on the
  // caller thread (stopRecording/close); no compound state to guard, only publication visibility.
  @Volatile private var timerJob: Job? = null
  @Volatile private var audioWatchdogJob: Job? = null
  @Volatile private var tempFile: File? = null

  fun setAudioInputHandler(handler: AudioInputHandler) {
    audioInputHandler = handler
  }

  fun setIncludeAudio(include: Boolean) {
    includeAudio = include
  }

  fun writeCompressedFrame(
      data: ByteArray,
      presentationTimeUs: Long,
      width: Int,
      height: Int,
      isCodecConfig: Boolean = false,
  ) {
    if (!_isRecording.value || rotating) return
    val justStarted =
        videoCaptureHandler.writeVideoFrame(data, presentationTimeUs, width, height, isCodecConfig)
    if (justStarted && !_hasStartedWriting.value) {
      _hasStartedWriting.value = true
      // Start the mic and elapsed timer aligned to the first keyframe so audio and duration line up
      // with the first decodable video sample. If there's no usable mic, keep recording video-only.
      if (includeAudio) {
        val audioStarted = audioInputHandler?.startRecording() ?: false
        if (!audioStarted) {
          Log.w(TAG, "Microphone unavailable; recording video only")
          videoCaptureHandler.markAudioUnavailable()
        } else {
          // The mic may initialize yet deliver no PCM (e.g. an emulator's virtual mic), so the AAC
          // encoder never produces a format and the muxer would wait for the audio track forever.
          // If audio isn't flowing shortly, fall back to video-only so the file still finalizes.
          // A no-op if audio already started the muxer.
          audioWatchdogJob = scope.launch {
            delay(AUDIO_READY_TIMEOUT_MS)
            if (_isRecording.value && !videoCaptureHandler.isMuxerStarted()) {
              Log.w(
                  TAG,
                  "Audio not flowing after ${AUDIO_READY_TIMEOUT_MS}ms; recording video only",
              )
              videoCaptureHandler.markAudioUnavailable()
            }
          }
        }
      }
      startTimer()
    }
  }

  /**
   * Starts recording. [codecConfig] is the most recent codec-config frame seen while streaming; it
   * primes the muxer's CSD so a recording that begins mid-stream still gets a video track (the SDK
   * sends the config only once, at stream start).
   */
  suspend fun startRecording(codecConfig: ByteArray? = null) {
    if (_isRecording.value) {
      return
    }
    _isRecording.value = true
    _recordingElapsedSeconds.value = 0
    _hasStartedWriting.value = false
    segmentCount = 1
    segmentSeconds = 0
    lastCodecConfig = codecConfig

    // Temp-file creation and the MediaMuxer/AAC-encoder setup are blocking I/O; keep them off the
    // main thread, mirroring stopRecording's withContext(IO). A frame that races this setup is a
    // safe no-op — writeVideoFrame returns early while the muxer is still null.
    withContext(Dispatchers.IO) {
      val file = createTempFile()
      tempFile = file

      videoCaptureHandler.resetState()
      videoCaptureHandler.prepare(file.canonicalPath, includeAudio)
      codecConfig?.let { videoCaptureHandler.setInitialCodecConfig(it) }
    }

    if (includeAudio) {
      audioInputHandler?.pcmDataCallback = { data, offset, size ->
        videoCaptureHandler.writeAudioPcm(data, offset, size)
      }
    }
    // Mic capture and the elapsed timer start on the first keyframe (see writeCompressedFrame).
  }

  private fun startTimer() {
    // Runs once per recording; a rotated segment reuses it so the elapsed time keeps counting.
    if (timerJob?.isActive == true) return
    timerJob = scope.launch {
      while (_isRecording.value) {
        delay(1000L)
        _recordingElapsedSeconds.value += 1
        segmentSeconds += 1
        if (segmentSeconds >= SEGMENT_SECONDS && !rotating) {
          segmentSeconds = 0
          launch { rotateSegment() }
        }
      }
    }
  }

  /**
   * Closes the current segment and opens the next one. The stream keeps running; frames that
   * arrive while the files swap are dropped, so there is a gap of up to one keyframe interval
   * between segments.
   */
  private suspend fun rotateSegment() {
    if (!_isRecording.value || rotating) return
    rotating = true
    try {
      audioWatchdogJob?.cancel()
      audioWatchdogJob = null
      if (includeAudio) {
        audioInputHandler?.pcmDataCallback = null
        audioInputHandler?.stopRecording()
      }
      _hasStartedWriting.value = false

      val finished = withContext(Dispatchers.IO) {
        val hadVideo = videoCaptureHandler.stopRecording()
        val file = tempFile
        tempFile = null
        if (hadVideo && file != null && file.length() > 0L) {
          runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
          }.getOrNull()
        } else {
          file?.delete()
          null
        }
      }
      finished?.let { onSegmentFinished?.invoke(it) }

      withContext(Dispatchers.IO) {
        val file = createTempFile()
        tempFile = file
        videoCaptureHandler.resetState()
        videoCaptureHandler.prepare(file.canonicalPath, includeAudio)
        lastCodecConfig?.let { videoCaptureHandler.setInitialCodecConfig(it) }
      }
      if (includeAudio) {
        audioInputHandler?.pcmDataCallback = { data, offset, size ->
          videoCaptureHandler.writeAudioPcm(data, offset, size)
        }
      }
      segmentCount += 1
    } finally {
      rotating = false
    }
  }

  suspend fun stopRecording(): RecordingResult {
    if (!_isRecording.value) {
      return RecordingResult.NoRecording
    }

    _isRecording.value = false
    _hasStartedWriting.value = false
    audioWatchdogJob?.cancel()
    audioWatchdogJob = null
    timerJob?.cancel()
    timerJob = null

    if (includeAudio) {
      audioInputHandler?.pcmDataCallback = null
      audioInputHandler?.stopRecording()
    }

    return withContext(Dispatchers.IO) {
      val hadVideo = videoCaptureHandler.stopRecording()
      val file = tempFile
      tempFile = null

      if (hadVideo && file != null && file.length() > 0L) {
        try {
          val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
          RecordingResult.Completed(uri)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to expose recording uri: ${e.message}", e)
          file.delete()
          RecordingResult.Failed
        }
      } else {
        Log.w(TAG, "No video data was recorded")
        file?.delete()
        RecordingResult.NoRecording
      }
    }
  }

  private fun createTempFile(): File {
    val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
    return File(dir, "temp_recording_${SystemClock.elapsedRealtime()}.mp4")
  }

  fun close() {
    timerJob?.cancel()
    audioWatchdogJob?.cancel()
    // Release an in-progress recording so the muxer/encoder don't leak if the owner is torn down
    // mid-recording (e.g. the activity is destroyed).
    if (_isRecording.value) {
      _isRecording.value = false
      audioInputHandler?.pcmDataCallback = null
      audioInputHandler?.stopRecording()
      runCatching { videoCaptureHandler.stopRecording() }
      tempFile?.delete()
      tempFile = null
    }
  }
}
