# BOBTV

App IPTV nativa en Kotlin y XML para Android 10 o posterior, manejada con el mando del TV Box. BOBTV **2.3**, código **7**, conserva `com.bobtv.helloworld` y el certificado de las versiones anteriores.

## Instalar como actualización

Entrega: **dist/BOBTV-2.3-Android10.apk**, con archivo SHA-256. Transfiérela por AnyDesk, ábrela en el gestor de archivos e instala como actualización. No hace falta ADB. Se conservan las entregas anteriores; Android normalmente impide instalar un código inferior sobre uno superior. Consulta [volumen y validación de 2.3](docs/VOLUMEN_2.3.md).

## Mando y configuración

| Botón | Viendo TV | En la lista |
|---|---|---|
| ↑ / ↓, CH+ / CH− | Siguiente / anterior, circular | ↑ / ↓ mover selección |
| 0–9 | Seleccionar número | Seleccionar número |
| OK | Confirmar número o abrir lista | Confirmar número o selección |
| Atrás | Cancelar número o confirmar salida | Cancelar número o cerrar lista |
| Menú | Configuración | Configuración |
| ← / Volumen − | Bajar un nivel de BOBTV | Bajar un nivel de BOBTV |
| → / Volumen + | Subir un nivel de BOBTV | Subir un nivel de BOBTV |
| Silencio | Alternar 0 / último nivel audible | Alternar 0 / último nivel audible |

Se admiten 1–999, ceros iniciales y confirmación tras 1,5 segundos, con OK o al tercer dígito. Todos los canales del catálogo están visibles y se pueden elegir por número; reordenar el M3U cambia los números. Se recuerda el canal por ID. La entrada numérica está desactivada en Configuración y salida. [Guía de numeración](docs/NUMERACION_CANALES.md).

Configuración incluye **Guardar log TXT en Descargas** y conserva **Exportar logs al portapapeles**. El archivo `BOBTV-log-AAAA-MM-DD_HH-mm-ss-SSS.txt` contiene el diagnóstico de la sesión en UTF-8, con datos sensibles redactados. Se guarda en la carpeta pública Descargas mediante MediaStore, sin permisos de almacenamiento, y se confirma con un aviso. Si falla la escritura, se intenta eliminar la entrada incompleta. Atrás devuelve el foco a la pantalla anterior. El aviso del canal dura cinco segundos. Los fallos ofrecen **Reintentar** y **Aceptar y cambiar** en vertical; arriba/abajo y OK eligen la acción. La confirmación de salida también usa arriba/abajo y abre con **Seguir viendo** seleccionado. Atrás abre la lista desde un error. No se cambia a otro canal automáticamente por un error.

El volumen tiene 100 incrementos: **0 es silencio y 100 es el máximo disponible**. Las flechas horizontales, Volumen ± y Silencio funcionan también durante carga, lista, Configuración, errores y confirmación de salida. Cada evento de repetición cambia un nivel; la liberación se consume. Subir desde 0 establece 1. El ajuste conserva el foco y la entrada numérica pendiente, y muestra una barra informativa abajo a la derecha dentro del margen seguro durante tres segundos desde la última pulsación. Configuración no incorpora un control interactivo de volumen.

BOBTV usa índices del dispositivo cuando empiezan en 0, permiten al menos 100 incrementos distintos y aceptan los cambios. En el resto de equipos intenta el máximo del dispositivo, comprueba el nivel permitido y atenúa su audio PCM con `(nivel / 100)²`. Conserva nivel y último nivel audible. El primer arranque aproxima el sonido previo con dB o proporción de índices y respeta el silencio inicial. Home y salida detienen la reproducción y restauran volumen y silencio del dispositivo; al volver se captura nuevamente ese estado. Las limitaciones del firmware se registran. La precisión audible y los códecs PCM siguen pendientes de prueba en el TV box físico.

## Disponibilidad y Unitel

Un corte, timeout, error de códec o agotamiento de reintentos no demuestra que la emisión esté offline. Los canales verificados permanecen seleccionables durante problemas transitorios. Confirmar indisponibilidad requiere dos evidencias concluyentes separadas al menos 15 segundos, con red operativa, para todas las fuentes del canal. Los errores de acceso y los resultados inconclusos se mantienen diferenciados.

Desde 2.2.1, **todos los canales del catálogo** aparecen desde la carga inicial, incluso sin red, pendientes de comprobación, con resultados inconclusos o confirmados sin señal. Se pueden intentar por lista o dígitos; mostrar un canal no lo cuenta como activo. Las flechas incluyen señales pendientes y excluyen las confirmadas offline. El arranque y **Aceptar y cambiar** siguen usando únicamente fuentes con evidencia positiva reciente. Unitel conserva su aviso personalizado.

Los probes comprueban manifiesto y muestra de segmento con un trabajador, diez segundos por fuente y rondas de hasta 30 fuentes/60 segundos. Se programan cada dos minutos y ceden prioridad durante arranque, cortes o presión de recursos. Las rondas reanudadas priorizan fuentes pendientes o comprobadas hace más tiempo para evitar repetir solo el inicio del catálogo. No crean otro reproductor.

## Reproducción en equipos de 1 GB

Se mantiene un único ExoPlayer con SurfaceView y decodificadores del sistema. Cuando hay variantes, se prefiere hasta 720p y 30 FPS; una señal única de calidad superior se intenta en su formato original. Los atributos de hardware del fabricante no acreditan rendimiento: se registra el decodificador realmente seleccionado.

| Perfil | Búfer mínimo / máximo | Arranque | Tras corte | Objetivo de muestras |
|---|---|---|---|---|
| Normal | 5 / 20 s | 1,5 s | 3 s | 16 MiB |
| Estabilización | 10 / 30 s | 1,5 s | 5 s | 24 MiB |
| Reforzado | 15 / 40 s | 1,5 s | 8 s | 32 MiB |
| Presión | 3 / 10 s | 1,5 s | 3 s | 8 MiB |

Los límites efectivos consideran heap, bitrate medido y ventana HLS. El objetivo de bytes no limita toda la memoria del proceso. Si los bytes o la ventana impiden el umbral temporal, se reanuda con contenido aprovechable.

Los cortes recurrentes aumentan gradualmente el retraso hasta un objetivo de **30 segundos** cuando lo permite la retención. Retraso del directo, búfer en RAM y tiempo de arranque son valores distintos. Una ventana corta limita el objetivo. Se conserva el búfer útil y se evita reiniciar en el borde; una fuente estable mantiene la latencia normal. La convergencia gradual puede tardar varios minutos. Cinco minutos de reproducción saludable reducen un nivel.

Las alternativas conservan tres pasadas, con esperas de 2 y 5 segundos. Hay límites adicionales de nueve preparaciones/120 segundos por ciclo y seis preparaciones automáticas por minuto. Tras agotamiento se aplica cooldown, sin ocultar el canal. Los cambios manuales siguen disponibles. Al volver la red se recupera el mismo canal seleccionado; Home y salida detienen reproducción, comprobaciones y listeners.

[Políticas y casos límite](docs/OPTIMIZACION_2.2.md). [Prueba física manual](docs/PRUEBA_TV_BOX.md).

## Diagnóstico

Logs de la sesión: hasta 2000 eventos y 256 KiB, con resúmenes acumulados, versiones, ABI, códecs, errores HTTP/Media3, fuentes, generaciones, reintentos, frames, audio, memoria, CPU, temperatura declarada, búfer y retraso. URLs y tokens sensibles se redactan. PSS puede omitir gráficos y servicios externos de códec. No se usa ADB ni se escribe caché de video.

## Administrar canales

Catálogo: [TylorDev/bobtv-canales](https://github.com/TylorDev/bobtv-canales), archivo [canales.m3u](https://github.com/TylorDev/bobtv-canales/blob/main/canales.m3u). Edita el M3U, conserva IDs y guarda en GitHub. La app consulta la lista al abrir, cada 30 minutos y con **Actualizar canales**; las actualizaciones automáticas pueden posponerse durante recuperación.

Se agrupan fuentes repetidas para los siete IDs actuales de Unitel, Red Uno y Bolivisión, conservando regiones. [Formato y ejemplos](docs/FUENTES.md). El catálogo admite hasta 999 canales, 16 fuentes por canal, 4096 fuentes totales, 1 MiB y líneas de hasta 8 KiB. Si es inválido, se conserva la copia anterior y la numeración. Caché y temporal están acotados; no se descargan logos ni video al disco.

Las [fuentes nuevas de octubre](docs/FUENTES_IPTV_2026-10-13.md) fueron examinadas desde el PC, no validadas físicamente en este TV Box. El audio MP2 de Red Uno requiere compatibilidad real del dispositivo; se conserva la alternativa AAC.

## Desarrollo y comprobaciones

- AGP 9.0.1, Gradle 9.1.0, Java 17 y Kotlin integrado.
- SDK de compilación 36, destino 33 y mínimo 29.
- Media3 / ExoPlayer **1.11.1**, HLS y PlayerView.
- Recursos XML y pruebas JVM, sin migración de stack.

Desde PowerShell:

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'
    $env:ANDROID_USER_HOME = Join-Path (Get-Location) '.gradle-user-home/android'
    $signingStore = Join-Path $env:USERPROFILE '.android/debug.keystore'
    .\gradlew.bat --no-daemon "-Pandroid.injected.signing.store.file=$signingStore" "-Pandroid.injected.signing.store.password=android" "-Pandroid.injected.signing.key.alias=androiddebugkey" "-Pandroid.injected.signing.key.password=android" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

Los informes quedan en app/build/reports. Los resultados de 2.3 se documentan en [volumen y validación](docs/VOLUMEN_2.3.md). Las pruebas de foco, teclas y ciclo de vida se ejecutan con Robolectric en JVM y se comprueban mediante assertions/logs, sin capturas ni emulador. No se hacen verificaciones visuales automatizadas. Las metas de PSS, mando, frames, continuidad y volumen audible requieren las pruebas manuales en el TV Box.
