# Validación de BOBTV 2.0

Pruebas realizadas el 13 de octubre de 2026. El usuario confirmó que **RED UNO se ve y se escucha correctamente en el TV box real** después de instalar el APK. Las pruebas detalladas del mando y del ciclo de vida corresponden al emulador Android TV 10.

## APK e instalación

- Entrega: `dist/BOBTV-2.0-Android10.apk`, aproximadamente 11,1 MiB.
- Identificador conservado: `com.bobtv.helloworld`.
- Versión 2.0, código 3; mínimo API 29, destino API 33 y compilación API 36.
- Firma APK v2 verificada. Certificado SHA-256: `6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68`.
- Se compiló la versión anterior 1.1 desde el commit `bb0f249`, se instaló en el emulador y se actualizó a 2.0 mediante `adb install -r`. Ambos APK tienen el mismo certificado.
- SHA-256 del APK de entrega: `f56fb0f5c50ba25995bc2f89c9fb19a06ec3d7a988675fd108f340868b754dc6`. También se incluye el archivo `.apk.sha256` junto al APK.
- La actividad aparece como destino del lanzador de TV mediante `LEANBACK_LAUNCHER`.

El emulador requirió un reinicio tras añadir el permiso de Internet a la instalación anterior. Después del reinicio accedió al catálogo y a las señales. No se desactivaron los controles de seguridad ni la validación TLS.

## Compilación y pruebas automatizadas

Se ejecutaron `:app:assembleDebug`, `:app:testDebugUnitTest` y `:app:lintDebug` con resultado satisfactorio.

| Pruebas | Casos | Resultado |
|---|---:|---|
| Lector M3U | 5 | Correctos |
| Catálogo y caché | 4 | Correctos |
| Reintentos | 1 | Correcto |

Los casos cubren los 13 canales incluidos en el APK, sus identificadores y direcciones originales, caracteres españoles, BOM, comentarios, atributos entre comillas, nombres con comas, entradas incompletas y rechazo de listas vacías o HTML. También cubren desconexión, errores HTTP, conservación de la última caché válida, caché dañada, altas, renombrados, bajas y recuperación del último canal por identificador. La secuencia de reintentos está limitada a 2 y 5 segundos.

Lint terminó con **0 errores y 5 advertencias**: destino API 33 conservado por el plan, tamaño del banner vectorial de TV, disponibilidad de otra versión de Gradle, orientación horizontal fija y uso de un contenedor raíz que conserva el foco del mando. No se cambió el SDK de destino ni el stack solicitado para eliminar estas advertencias.

## Pruebas en Android TV API 29

Dispositivo virtual `BOBTV_Android10_TV`, perfil TV 1080p, 2 GiB de RAM, decodificación del emulador. Se hicieron pruebas explícitas mediante eventos del mando y revisión visual de la lista, el foco amarillo con borde, los textos, los márgenes y los mensajes de error.

RED UNO HTTPS mostró video decodificado, con pistas de audio y video seleccionadas y salida de audio registrada por Android. El usuario también confirmó imagen y sonido correctos en el TV box real; la comprobación física fue comunicada por el usuario mediante la sesión de prueba con AnyDesk.

RED UNO Santa Cruz HTTP mostró imagen inicial, confirmando el acceso HTTP permitido. La emisión posteriormente falló con `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`; se registró como señal inestable, sin cambiar su dirección original.

Las pruebas del mando y del ciclo de vida se completaron sin fallos:

- El canal guardado se recuperó después de reiniciar el proceso de la app.
- El nombre y la ayuda desaparecieron después de cinco segundos.
- Mover la selección de la lista y cerrarla con Atrás no cambió la reproducción.
- Arriba, abajo, CH+ y CH− cambiaron de canal; se comprobó el recorrido circular entre el primero y el último.
- El volumen se delegó al sistema sin cambiar el canal.
- La confirmación de salida enfocó inicialmente **Seguir viendo**; izquierda, derecha y OK permitieron cancelar o salir.
- Home liberó el reproductor; volver a la app recuperó el canal seleccionado.
- Al desactivar la conexión del emulador y reiniciar la app, el catálogo guardado permaneció disponible e idéntico. La interfaz informó que conservaba los canales.
- Confirmar **Salir** liberó el reproductor. No se observaron excepciones fatales de Android durante estas comprobaciones.

## Actualizaciones reales desde GitHub

Se comprobó el repositorio público [TylorDev/bobtv-canales](https://github.com/TylorDev/bobtv-canales), rama `main`, con el mismo APK definitivo instalado durante toda la prueba:

| Cambio publicado | Resultado en la app |
|---|---|
| Agregar un canal temporal | El catálogo pasó de 13 a 14 canales |
| Renombrar ese canal conservando su `tvg-id` | Apareció **PRUEBA RENOMBRADA** en la lista |
| Quitar el canal temporal | El catálogo volvió a 13 canales |

El SHA-256 del APK y `lastUpdateTime` de la instalación permanecieron idénticos. Las actualizaciones y la navegación de la lista no generaron una nueva reproducción del canal actual. Al terminar, el archivo publicado volvió a contener exactamente los 13 identificadores y las 13 direcciones originales.

Durante la prueba, GitHub tardó aproximadamente cinco minutos en propagar cada cambio a la red del emulador. Se repitió **Actualizar canales** hasta recibirlo. La app solicita el catálogo sin caché local y sin compresión; conserva la lista disponible durante los retrasos o fallos del servidor.

Una muestra durante la reproducción registró aproximadamente 73 MiB de PSS total y 5,1 MiB de objetos asignados en el heap Java. Es una muestra del emulador, no una garantía de consumo del TV box. El reproductor usa un objetivo de búfer de 16 MiB, hasta 20 segundos y un solo canal preparado.

## Disponibilidad de las fuentes originales

Consulta de las listas HLS desde la red del equipo de desarrollo a las 18:52 UTC. Un HTTP 200 indica acceso al archivo M3U8; no demuestra reproducción continua de todos sus segmentos ni disponibilidad desde otra red.

| Canal | Resultado |
|---|---|
| UNITEL Santa Cruz | HTTP 403 |
| UNITEL La Paz | HTTP 403 |
| UNITEL Cochabamba | HTTP 403 |
| RED UNO | HTTP 200; video y pistas de audio en el emulador |
| RED UNO Santa Cruz | HTTP 200; video inicial, transmisión inestable |
| BOLIVISIÓN Bolivia | HTTP 403 |
| BOLIVISIÓN Santa Cruz | HTTP 403 |
| ATB La Paz | Tiempo de espera agotado |
| BOLIVIA TV | HTTP 200, lista HLS |
| RTP | HTTP 200, lista HLS |
| GIGAVISIÓN | HTTP 200, lista HLS |
| ZURACA TV | HTTP 200, lista HLS |
| CTV | HTTP 404 |

Los errores 403 pueden reflejar restricciones o requisitos del emisor; no se confirmó su causa. No se sustituyeron ni eliminaron estas fuentes: se conservaron los 13 enlaces entregados por el usuario. Se pueden corregir posteriormente en GitHub sin recompilar.

## Prueba física

El usuario confirmó instalación y reproducción con imagen y sonido en RED UNO. No hay un dispositivo físico conectado por ADB y esta sesión no dispone de control de AnyDesk: se distingue esta confirmación del usuario de la instrumentación del emulador. Queda disponible [PRUEBA_TV_BOX.md](PRUEBA_TV_BOX.md) para comprobar todas las funciones del mando en el dispositivo real. El APK se instala mediante el gestor de archivos sin USB ni depuración inalámbrica.

La confirmación del TV box se recibió antes del último ajuste de descarga del catálogo (`Accept-Encoding: identity`). El reproductor, la interfaz y los permisos de reproducción se conservaron; el APK definitivo se volvió a compilar y verificar después de ese ajuste.
