# LongRec

Graba video largo con Ray-Ban Meta (Gen 1 y Gen 2) sin el límite de 3 minutos de la grabadora nativa.

La app del teléfono recibe el stream de la cámara de las gafas con el
[Meta Wearables Device Access Toolkit](https://github.com/facebook/meta-wearables-dat-ios)
y lo graba ella misma, en segmentos de 5 minutos que se guardan solos en Fotos (iPhone) o en
`Movies/LongRec` (Android).

- `ios/`: SwiftUI, iOS 17.2+
- `android/`: Kotlin, Android 12+ (`minSdk` 31)

## Estado

Prototipo sin probar con gafas reales. iPhone compila; Android está escrito pero sin compilar.
Por medir: resolución real del stream (el SDK usa calidad baja, 24 fps HEVC), si la sesión
sobrevive con el teléfono bloqueado y cuánto aguantan batería y temperatura de las gafas.

## Uso

1. Activa el modo desarrollador de las gafas en la app Meta AI.
2. iPhone: abre `ios/CameraAccess.xcodeproj`, pon tu equipo en Signing & Capabilities y ejecuta.
3. Android: abre `android/` en Android Studio y ejecuta.

El SDK está en developer preview: no se puede publicar en las tiendas, solo instalar en tus dispositivos.

## Origen y licencia

Parte de los samples oficiales *CameraAccess* de Meta. Los archivos derivados conservan su
encabezado de copyright. El uso del SDK está sujeto a los
[términos de desarrollador de Meta Wearables](https://wearables.developer.meta.com/terms).
