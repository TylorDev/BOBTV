# BOBTV

App IPTV nativa en Kotlin para un TV box con Android 10 o posterior. Se abre directamente en el último canal utilizado y se maneja con el control remoto. Interfaz en español con texto grande, alto contraste y márgenes de seguridad para TV.

## Instalar sin USB

APK de entrega: **dist/BOBTV-2.0-Android10.apk**.

1. Transfiere el APK al TV box mediante AnyDesk.
2. En el TV box, abre el gestor de archivos y selecciona el APK.
3. Si Android lo solicita, permite instalar aplicaciones desconocidas para ese gestor.
4. Instala como actualización de la app anterior y abre **BOBTV**.

Se mantiene el identificador com.bobtv.helloworld y el certificado de depuración anterior. La versión es **2.0**, código **3**, con mínimo Android 10 / API 29. Para la prueba inicial puedes elegir **RED UNO** desde la lista; varias fuentes originales devolvieron errores desde la red de desarrollo. Consulta [la validación](docs/VALIDACION.md).

Si después de actualizar no funciona ninguna conexión, reinicia el dispositivo una vez. En el emulador Android TV 10 los permisos nuevos de Internet se recargaron correctamente tras reiniciarlo.

## Control remoto

| Botón | Mientras ves TV | Dentro de la lista |
|---|---|---|
| Arriba / abajo | Siguiente / anterior, con recorrido circular | Mover la selección |
| OK | Abrir la lista | Ver el canal seleccionado |
| Atrás | Confirmar salida | Cerrar la lista |
| CH+ / CH− | Siguiente / anterior | — |
| Volumen | Control del sistema | Control del sistema |

En los mensajes con dos botones, usa izquierda / derecha y OK. Al salir, **Seguir viendo** queda seleccionado inicialmente. El nombre del canal y la ayuda desaparecen después de cinco segundos. La lista y los errores permanecen hasta que actúes.

Una señal fallida se reintenta dos veces, después de 2 y 5 segundos, y luego permite **Reintentar** o **Elegir canal**. La app mantiene el canal seleccionado. No reproduce en segundo plano.

## Administrar canales sin otra build

Repositorio: [TylorDev/bobtv-canales](https://github.com/TylorDev/bobtv-canales).

La app consulta [canales.m3u en main](https://github.com/TylorDev/bobtv-canales/blob/main/canales.m3u). Edita desde GitHub y guarda con **Commit changes**. La guía del repositorio incluye el formato para altas, cambios y bajas.

Actualiza al abrir, cada 30 minutos mientras está activa y mediante **Actualizar canales** al final de la lista. La actualización no interrumpe el video. Conserva la última lista válida y dispone de una copia inicial con los 13 canales.

GitHub puede tardar unos minutos en servir un cambio recién guardado. Si todavía aparece la lista anterior, espera y pulsa **Actualizar canales** otra vez.

Los nombres, el orden y las URLs se administran en el M3U. Conserva el tvg-id de un canal cuando lo renombres: permite recordar la selección aunque cambie de posición. Si desaparece, la siguiente ejecución abre el primero disponible. Se admiten fuentes HLS HTTP y HTTPS.

Fuente canónica: https://raw.githubusercontent.com/TylorDev/bobtv-canales/main/canales.m3u

## Desarrollo

Abre esta carpeta en Android Studio y ejecuta el módulo app.

- Kotlin integrado en Android Gradle Plugin **9.0.1**, Gradle **9.1.0**, Java 17.
- SDK de compilación **36**, destino **33**, mínimo **29**.
- Media3 / ExoPlayer **1.11.1**, módulos HLS y PlayerView.
- Un reproductor con decodificadores del sistema y fallback; búfer de hasta 20 segundos con objetivo de 16 MiB.
- Recursos XML para colores, dimensiones, tema, foco, icono y banner.
- La URL del catálogo es fija; no se requieren credenciales de GitHub en el dispositivo.

Compilación y comprobaciones desde PowerShell:

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:GRADLE_USER_HOME = Join-Path (Get-Location).Path '.gradle-user-home'
    .\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

El APK de Gradle está en app/build/outputs/apk/debug/app-debug.apk. Copia la entrega a dist para conservarla entre compilaciones. El certificado de depuración existente debe conservarse para las futuras actualizaciones.

Las diez pruebas cubren el M3U inicial, UTF-8/BOM, comentarios, comas, entradas inválidas, caché, errores de red, cambios de catálogo y los dos reintentos. Los informes de Gradle quedan en app/build/reports.

Se creó el dispositivo virtual **BOBTV_Android10_TV**, con Android TV API 29 y perfil 1080p. Sus archivos están en .tooling/avd y queda registrado para Android Studio. Al finalizar las pruebas se cierra el emulador; puedes iniciarlo desde Device Manager.

Referencias: [Media3](https://developer.android.com/jetpack/androidx/releases/media3), [aplicaciones para TV](https://developer.android.com/training/tv/get-started/create) y [SDK mínimo y de destino](https://developer.android.com/guide/topics/manifest/uses-sdk-element).
