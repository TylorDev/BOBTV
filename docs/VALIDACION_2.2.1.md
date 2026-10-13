# Corrección BOBTV 2.2.1 — 13 de octubre de 2026

## Cambios

La versión anterior ocultaba canales que nunca habían tenido evidencia positiva y solo exceptuaba Unitel. Además, al arrancar la primera señal se cancelaban los probes restantes; algunas rondas posteriores repetían fuentes ya comprobadas antes de llegar al resto del catálogo.

Todos los canales del catálogo aparecen al cargar la lista y se pueden intentar por lista o número, incluso sin conexión o con un resultado inconcluso. La navegación con flechas incluye canales pendientes y excluye señales confirmadas sin emisión. Los estados reales, la numeración, la confirmación de indisponibilidad y las restricciones de los cambios automáticos se conservan. Las rondas priorizan fuentes sin comprobar o comprobadas hace más tiempo.

Configuración añade **Guardar log TXT en Descargas**, con foco inicial y navegación ↑↓ entre las dos opciones. Se mantiene el portapapeles. La escritura UTF-8 se ejecuta fuera del hilo de interfaz, usa el snapshot redactado de SessionLog y genera un nombre con fecha/hora/milisegundos. MediaStore mantiene la entrada pendiente hasta cerrar el archivo y elimina la entrada si falla la escritura/publicación. Android 10 o posterior permite escribir archivos propios en Downloads sin permisos de almacenamiento: [documentación oficial](https://developer.android.com/training/data-storage/shared/media). Si el proveedor falla también al eliminar, el error adicional queda en el log.

## Entrega

- APK: `dist/BOBTV-2.2.1-Android10.apk`.
- Versión **2.2.1**, código **6**, aplicación `com.bobtv.helloworld`.
- Android mínimo **10 / API 29**, destino **33**, compilación **36**.
- Tamaño: **11.597.810 bytes**.
- SHA-256: `6332ef41f329403ac1d01c50697a8908b496a069fb84fc6b60aef37f04b020c8`.
- Firma APK v2 verificada. Certificado SHA-256: `6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68`, coincidente con la entrega anterior.
- Archivo de comprobación: `dist/BOBTV-2.2.1-Android10.apk.sha256`.

Puede instalarse sobre 2.2 sin desinstalar ni borrar preferencias o caché. Las entregas anteriores de `dist/` se conservan.

## Comprobaciones

`:app:assembleDebug`, `:app:testDebugUnitTest` y `:app:lintDebug` completados correctamente con Gradle `--no-daemon`. **115 pruebas JVM, 21 suites, cero fallos y cero errores.** Se adaptaron los casos de visibilidad al catálogo completo y se añadieron regresiones para los 13 canales iniciales antes de cualquier probe/después de timeouts y para el avance de rondas interrumpidas. La firma y los metadatos del APK se verificaron con las herramientas del SDK.

No se inició emulador, ADB ni servidor de desarrollo y no se hicieron verificaciones visuales. Al finalizar las comprobaciones no quedaron procesos Java de la tarea.

Lint: **cero errores y las cinco advertencias existentes** (target API 33, tamaño del banner vectorial, versión de Gradle disponible, orientación fija y FrameLayout raíz). Sin advertencias nuevas.

## Pendiente en el TV Box

La disponibilidad real de cada emisión depende del origen, la red y los códecs. Las pruebas JVM no acreditan reproducción física ni el proveedor MediaStore del firmware. Comprobar la lista completa al abrir, selección de Red Uno/Bolivisión y otros por número/flechas, guardado del TXT con acentos en Descargas, aviso de éxito/error y repetición del guardado. Consultar [la guía manual](PRUEBA_TV_BOX.md).
