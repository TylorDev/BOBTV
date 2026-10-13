# BOBTV

App IPTV nativa en Kotlin para un TV box con Android 10 o posterior. Se abre directamente en el último canal utilizado y se maneja con el control remoto. Interfaz en español con texto grande, alto contraste y márgenes de seguridad para TV.

## Instalar sin USB

APK de entrega: **dist/BOBTV-2.1-Android10.apk**. La copia de esta tarea queda también en **dist/BOBTV-2.1-Configuracion-Android10.apk**.

1. Transfiere el APK al TV box mediante AnyDesk.
2. En el TV box, abre el gestor de archivos y selecciona el APK.
3. Si Android lo solicita, permite instalar aplicaciones desconocidas para ese gestor.
4. Instala como actualización de la app anterior y abre **BOBTV**.

Se mantiene el identificador com.bobtv.helloworld y el certificado de depuración anterior. La versión es **2.1**, código **4**, con mínimo Android 10 / API 29. Consulta [la validación de esta versión](docs/VALIDACION_2.1.md).

Si después de actualizar no funciona ninguna conexión, reinicia el dispositivo una vez. En el emulador Android TV 10 los permisos nuevos de Internet se recargaron correctamente tras reiniciarlo.

## Control remoto

| Botón | Mientras ves TV | Dentro de la lista |
|---|---|---|
| Arriba / abajo | Siguiente / anterior en línea, con recorrido circular | Mover la selección |
| 0–9 | Ir al canal por número | Ir al canal por número |
| OK | Confirmar el número pendiente o abrir la lista | Confirmar el número pendiente o ver la selección |
| Atrás | Cancelar el número pendiente o confirmar salida | Cancelar el número pendiente o cerrar la lista |
| CH+ / CH− | Siguiente / anterior | — |
| Menú | Abrir Configuración | Abrir Configuración |
| Volumen | Control del sistema | Control del sistema |

Escribe de uno a tres dígitos: `5`, `05` y `005` seleccionan el mismo canal. El cambio se confirma tras 1,5 segundos sin escribir, al completar tres dígitos o con OK. Los números se asignan por el catálogo M3U completo, del 1 al 999: ocultar canales sin señal deja huecos y conserva los números restantes; reordenar el catálogo cambia la numeración. Consulta [la guía de numeración y cancelaciones](docs/NUMERACION_CANALES.md).

En los mensajes con dos botones, usa izquierda / derecha y OK. Al salir, **Seguir viendo** queda seleccionado inicialmente. El aviso `05 - NOMBRE DEL CANAL` y la ayuda desaparecen después de cinco segundos, también durante carga o fallo. La lista y los errores permanecen hasta que actúes.

Si una fuente falla, la app prueba las alternativas del mismo canal y permite dos nuevas pasadas, después de 2 y 5 segundos. Si se agotan, registra el diagnóstico y muestra **Aceptar y cambiar**. Al aceptar pasa al siguiente canal en línea; Atrás abre la lista sin cambiar automáticamente. Las flechas y CH+/CH− no saltan este aviso; también puedes elegir otro canal disponible por número. No reproduce ni comprueba señales en segundo plano.

## Configuración y diagnóstico

Pulsa **Menú**, o **OK → Configuración** al pie de la lista. El menú contiene únicamente **Exportar logs al portapapeles**. Atrás devuelve a la pantalla anterior y conserva el foco.

La exportación copia texto con la versión, dispositivo, canal, fuente, tiempos de conexión, errores HTTP/Media3, excepciones, reintentos y cambios de fuente. El historial corresponde a la sesión actual, con un máximo de 2.000 eventos y 256 KiB; se descartan los más antiguos. Las credenciales y los parámetros de las URLs se ocultan tanto en el informe como en Logcat.

Android 10–12 muestra una confirmación de copia; Android 13 o posterior utiliza la confirmación del sistema.

## Canales en línea y fuentes alternativas

La lista y el recorrido del mando muestran solo canales con alguna fuente confirmada disponible desde la red del TV box. Se comprueba el manifiesto HLS y una muestra de datos de un segmento, al abrir, al actualizar y cada dos minutos mientras la app está activa. Las comprobaciones tienen un límite de diez segundos por fuente y usan dos tareas simultáneas; no crean otro reproductor.

Mientras se comprueban fuentes aparece el progreso. Si no hay señales disponibles, la lista ofrece **Actualizar canales** y **Configuración**. Los canales ocultos permanecen en el catálogo y pueden reaparecer al recuperar señal. Al abrir, se recupera el último canal si está disponible o se reproduce el primero en línea.

Unitel, Red Uno y Bolivisión admiten múltiples URLs por canal repitiendo su tvg-id. Cada variante regional sigue siendo una entrada independiente. Consulta [la guía con los siete identificadores y ejemplos de dos fuentes](docs/FUENTES.md), también publicada en el [repositorio de canales](https://github.com/TylorDev/bobtv-canales#fuentes-alternativas-de-unitel-red-uno-y-bolivisión).

## Administrar canales sin otra build

Repositorio: [TylorDev/bobtv-canales](https://github.com/TylorDev/bobtv-canales).

La app consulta [canales.m3u en main](https://github.com/TylorDev/bobtv-canales/blob/main/canales.m3u). Edita desde GitHub y guarda con **Commit changes**. La guía del repositorio incluye el formato para altas, cambios y bajas.

Actualiza al abrir, cada 30 minutos mientras está activa y mediante **Actualizar canales** al final de la lista. La actualización no interrumpe el video y actualiza sus alternativas. Conserva la última lista válida completa y dispone de una copia inicial con los 13 canales.

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
    $env:ANDROID_USER_HOME = Join-Path (Get-Location).Path '.gradle-user-home/android'
    $signingStore = Join-Path $env:USERPROFILE '.android/debug.keystore'
    .\gradlew.bat --no-daemon "-Pandroid.injected.signing.store.file=$signingStore" "-Pandroid.injected.signing.store.password=android" "-Pandroid.injected.signing.key.alias=androiddebugkey" "-Pandroid.injected.signing.key.password=android" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

El APK de Gradle está en app/build/outputs/apk/debug/app-debug.apk. Copia la entrega a dist para conservarla entre compilaciones. El certificado de depuración existente debe conservarse para las futuras actualizaciones.

Las 77 pruebas cubren catálogo y caché, agrupación de fuentes, regiones separadas, disponibilidad y recuperación, errores HTTP, límites de lectura, segmentos HLS, cancelación, concurrencia, reintentos y logs. Incluyen 33 casos de numeración, recorrido circular, teclas del mando y selección numérica con tiempo simulado. Los informes de Gradle quedan en app/build/reports.

Esta entrega se valida mediante compilación, pruebas unitarias y lint. No se realizaron verificaciones visuales ni se inició el emulador. La [validación de 2.0](docs/VALIDACION.md) conserva el historial de las pruebas anteriores.

Referencias: [Media3](https://developer.android.com/jetpack/androidx/releases/media3), [aplicaciones para TV](https://developer.android.com/training/tv/get-started/create) y [SDK mínimo y de destino](https://developer.android.com/guide/topics/manifest/uses-sdk-element).
