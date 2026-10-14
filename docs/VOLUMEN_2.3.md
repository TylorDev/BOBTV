# Volumen 0–100 — BOBTV 2.3

## Comportamiento

La app ofrece 100 incrementos y 0 como silencio. ← y Volumen − bajan uno; → y Volumen + suben uno, incluyendo cada evento de repetición del mando. Se consume también la liberación para que Android no haga un segundo ajuste. `KEYCODE_VOLUME_MUTE` alterna 0 y el último nivel audible; subir desde 0 establece 1. `KEYCODE_MUTE` corresponde al micrófono y no se redefine.

Las teclas se procesan al principio de `TvScreen.handleKeyEvent`, antes de cancelar números, deduplicar otras teclas o navegar. Esto aplica durante reproducción, carga, reintento, espera de red, lista, Configuración, errores y salida. Se conservan foco, dígitos y temporizador numérico. La barra informativa vive dentro de `safe_content`, abajo a la derecha, no admite foco ni interacción y se oculta tres segundos después de la última pulsación. No se añade un slider a Configuración.

Los botones de salida y fallo son verticales. Arriba/abajo eligen y OK confirma. La salida siempre abre con **Seguir viendo** seleccionado. En errores, arriba elige **Reintentar** y abajo **Aceptar y cambiar** o **Elegir otro canal**; la app ya no consume estas flechas sin mover el foco.

## Audio y ciclo de vida

`VolumeController` contiene la política Kotlin independiente de Android; `AndroidDeviceAudio` adapta `AudioManager.STREAM_MUSIC` y `StoredVolumePreferences` conserva `volume_level` y `volume_last_audible` en las preferencias `bobtv`, junto al canal existente. El permiso `MODIFY_AUDIO_SETTINGS` es normal y no exige un diálogo de permiso.

- **Nativo:** mínimo 0, máximo al menos 100 y volumen no fijo. Se mapea el nivel con `floor(nivel × máximo / 100)`, produciendo 101 índices distintos. La ganancia de ExoPlayer es 1. Cada escritura comprueba índice y silencio efectivos; ante un rechazo cambia al modo app conservando el nivel elegido. No se recorren los índices audiblemente para sondear el firmware.
- **App:** ante rango insuficiente, mínimo distinto de 0, política fija o rechazo. Primero se pone la ganancia local a 0, luego se intenta máximo y desactivar silencio del sistema, y se lee el resultado. Si el firmware lo limita, se conserva su estado permitido y se registra `Limitation`. La ganancia final es `(nivel / 100)²`: 0 → 0, 1 → 0,0001, 50 → 0,25 y 100 → 1.
- **Primera migración:** para modo app se estima la amplitud anterior con `10^((dB_original − dB_efectivo) / 20)`. Se consulta HDMI/ARC/eARC cuando está disponible; de otro modo, altavoz integrado. Se usa el índice realmente leído tras el ajuste. Si los dB faltan, son NaN o el dB efectivo no es finito, se usa `índice_original / índice_efectivo`. Un dB original de −∞ representa silencio. Se limita la ganancia a 0–1 y se convierte mediante `floor(100 × sqrt(ganancia))`, sin redondear hacia arriba. El silencio inicial sigue en 0; el último nivel audible conserva la estimación, con mínimo 1. En modo nativo el índice previo se convierte a la escala por truncamiento.
- **Salida/Home:** `onStop` libera ExoPlayer antes de restaurar índice y silencio capturados al entrar. Se verifica la restauración y se registra cualquier rechazo. Al volver, `onStart` captura el estado externo otra vez y aplica el nivel guardado antes de poder reproducir. Start/stop repetidos no reemplazan accidentalmente la captura. Si no se puede leer el dispositivo, se registra la limitación y se dejan sus ajustes intactos.

`TvPlayer.setVolume(Float)` conserva la ganancia aunque todavía no exista ExoPlayer. Se aplica al construirlo y antes de preparar una fuente, también después de liberación o recuperación por memoria. El reproductor inicia con ganancia 0 hasta que el controlador la establezca.

Se fuerza la decodificación a PCM mediante `PcmAudioSink`: rechaza formatos comprimidos para passthrough incluso cuando cambia la capacidad HDMI del sink delegado. El selector y el sink desactivan audio offload y tunneling. La reproducción requiere un decodificador local compatible; el passthrough ya no puede suplir un códec ausente. La salida audible y el comportamiento HDMI del firmware siguen pendientes del TV box físico.

Referencias consultadas: [AudioManager](https://developer.android.com/reference/android/media/AudioManager), [Player.setVolume](https://developer.android.com/reference/androidx/media3/common/Player#setVolume(float)), [DefaultAudioSink 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java) y [ForwardingAudioSink 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/ForwardingAudioSink.java). Las API se contrastaron con Context7 y las clases Media3 1.11.1 del proyecto.

## Entrega

- APK debug: `dist/BOBTV-2.3-Android10.apk`, **11.810.667 bytes**.
- Versión **2.3**, código **7**, aplicación `com.bobtv.helloworld`; mínimo API **29**, destino **33**, compilación **36**.
- SHA-256: `aef34a04cd3283f2a44491bc1f4639a2d62b210dce1dae4046cd5f4b5f74a49d`.
- Firma APK v2 verificada con apksigner. Certificado SHA-256: `6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68`, coincidente con 2.2.1.
- Archivo de comprobación: `dist/BOBTV-2.3-Android10.apk.sha256`. Las entregas anteriores se conservan.

Se instala como actualización sin desinstalar ni borrar las preferencias del canal. El primer inicio migra el volumen anterior únicamente si todavía no existe `volume_level`.

## Validación

La suite añade pruebas Kotlin del controlador y teclas, y pruebas de integración Robolectric con assertions/logs de foco, temporizadores, preferencias, PCM, ExoPlayer y ciclo de vida real de MainActivity. No se hacen capturas ni verificaciones visuales y no se inicia un emulador.

Casos: límites 0/100; 100 incrementos distintos; curva progresiva; silencio y último audible; repeticiones/liberación; equivalencia de flechas y botones; rangos 15/100/127/200; mínimo no cero; volumen fijo, máximo limitado, rechazo inicial/tardío y excepciones; estimación dB y fallback; silencio inicial; todas las pantallas; dígitos y timer pendientes; navegación vertical/OK; persistencia; recreación y ganancia previa a playback; Home, regreso y salida normal. El adaptador se prueba en API 29 y 33, incluyendo la selección de HDMI/ARC o altavoz para dB.

**Comprobación final completada el 13 de octubre de 2026:** `:app:assembleDebug`, `:app:testDebugUnitTest` y `:app:lintDebug` finalizaron correctamente en la misma ejecución con Gradle `--no-daemon`. **147 pruebas en 27 suites, cero fallos, cero errores y cero omitidas**; son 32 casos adicionales respecto a 2.2.1. Se utilizó JBR **25.0.3**, con destino de compilación JVM **17**. Los informes quedan en `app/build/reports` y los logs de los tests en `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

Lint: **cero errores y seis advertencias**. Cinco ya existían (target API 33, tamaño del banner vectorial, versión de Gradle, orientación fija y FrameLayout raíz); la sexta indica que existe Robolectric 4.17, frente a la versión 4.16 fijada y comprobada para estas pruebas. No hay advertencias nuevas en el código de audio o mando. Los avisos del entorno sobre métricas del SDK, XML del SDK y acceso nativo del JBR no impidieron las comprobaciones.

Los logs de la prueba de MainActivity confirman `gain=0.16000001 before prepare/play`, después `Player released` y `Restore device index=3 muted=true verified`, y al volver una nueva captura de índice 7 con el nivel de BOBTV guardado en 0. La prueba de pantalla registra conservación del foco en los ocho estados; se comprueba el foco del árbol de vistas y los temporizadores, sin renderizar capturas. Los logs pertenecen a Robolectric, no al TV box físico.

Tras compilar, probar y verificar la firma, no quedaron procesos Java de la tarea. No se iniciaron servidores, emulador ni ADB y se conservaron los procesos ajenos. La prueba audible se detalla en [PRUEBA_TV_BOX.md](PRUEBA_TV_BOX.md) y queda pendiente; las pruebas de JVM no certifican audio físico ni CEC.
