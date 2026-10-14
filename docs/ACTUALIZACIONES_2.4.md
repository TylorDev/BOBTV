# Actualizaciones — BOBTV 2.4

## Cliente

WorkManager programa un único trabajo periódico cada seis horas, con red conectada, y una comprobación al abrir si venció el intervalo. Android decide el momento exacto de ejecución según red y restricciones de energía; una app forzada a detenerse puede necesitar abrirse para reactivar el trabajo. La búsqueda manual se ejecuta en un hilo propio inmediatamente, sin esperar al planificador. Ambas rutas comparten el repositorio y un bloqueo que evita descargas simultáneas.

El cliente consulta `https://github.com/TylorDev/BOBTV/releases/latest/download/update.json` sin tokens ni cuenta de GitHub. Solo admite versiones estables, código incremental, paquete esperado, notas acotadas y URL del APK fijada al tag de la misma versión. Las redirecciones se limitan a HTTPS y los hosts de descargas de GitHub. La descarga no se carga completa en RAM: se escribe con un buffer de 16 KiB, con límite de 64 MiB por APK y comprobación previa de espacio para descarga e instalación.

Se verifican longitud, SHA-256, paquete, nombre/código de versión, Android mínimo y certificado frente a la app instalada. Antes de crear la sesión de instalación se vuelve a validar el archivo para rechazar modificaciones posteriores. Se guarda el APK y el manifiesto atómico en almacenamiento privado; las descargas incompletas se eliminan. Los fallos de red se reintentan con backoff exponencial, hasta tres reintentos por ejecución.

Un parche descargado automáticamente se instala en el próximo inicio de BOBTV, antes de reproducir. El botón manual intenta hacerlo inmediatamente mientras la app está en primer plano; al pasar a segundo plano se conserva para otro inicio. Se detienen reproducción y sondeos y se restaura el audio del dispositivo antes de iniciar instalación. No se desinstala ni se borran las preferencias `bobtv`.

PackageInstaller recibe una sesión de actualización de la propia app. Desde API 31 se solicita `USER_ACTION_NOT_REQUIRED`, con `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Se comprueba autorización de origen mediante `canRequestPackageInstalls`; si falta, se abre la configuración correspondiente. `STATUS_PENDING_USER_ACTION` conserva la confirmación y se muestra con la Activity reanudada. Cancelación o fallo vuelve a la app sin repetir automáticamente el diálogo dentro de ese arranque. Las sesiones sin confirmar abandonadas por muerte del proceso se reconstruyen a partir del APK pendiente.

Android 10/11 requiere confirmar el instalador en dispositivos normales. Manteniendo destino API 33, la instalación silenciosa depende de las condiciones del sistema; Android 16 o posterior exige un destino más reciente para esa modalidad y puede pedir confirmación. La instalación puede cerrar el proceso de BOBTV; al volver a abrirlo se conserva canal y volumen. La app no requiere root ni administración especial. [Reglas de PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).

Configuración conserva sus dos acciones de logs y añade versión/notas y búsqueda de parches. Arriba/abajo recorre los cuatro botones, OK activa el seleccionado y Atrás regresa. El panel permite desplazarse dentro del margen seguro. Las notas usan el archivo de la versión instalada, incorporado desde `releases/2.4.md`, sin consultar las notas de otra versión. Arriba/abajo desplaza la lectura y OK/Atrás devuelve el foco a Versión. El estado y progreso no admiten foco y las teclas de volumen mantienen su función.

## Distribución

Repositorio público `TylorDev/BOBTV`, rama `main`, conservando el historial anterior. La etiqueta `v2.4` corresponde a versión 2.4, código 8, aplicación `com.bobtv.helloworld`, mínimo 29, destino 33 y compilación 36. El APK release no es debuggable; mantiene el certificado SHA-256 `6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68` para actualizar 2.3 y anteriores.

Actions ejecuta las comprobaciones en commits y PRs. Los tags de versión habilitan un trabajo separado con secretos de firma y `contents: write`. Las actions están fijadas a commits. Se valida tag/versión/notas y un código superior al último release publicado. El keystore temporal se elimina al finalizar; los builds de PR no reciben secretos. Los assets se suben a un borrador, se descargan para verificar sus bytes y solo después se publica como última versión. Un release ya publicado no se reemplaza: las correcciones requieren otro código y etiqueta.

Contrato `update.json`: `versionName`, `versionCode`, `applicationId`, `minSdk`, `apkUrl`, `sizeBytes`, `sha256`, `notes`. Los valores del APK se contrastan con Gradle mediante aapt y apksigner. Las notas de la release, el manifiesto y el APK derivan del mismo archivo fuente. La distribución conserva todas las releases anteriores.

## Validación

Pruebas JVM/Robolectric en API 29 y 33: integridad y firma, paquete y versiones erróneas, Android incompatible, cortes de descarga, espacio insuficiente, persistencia, concurrencia, frecuencia, sesiones de instalación, callbacks ajenos, confirmación y cancelación. Las pruebas de mando y notas usan assertions sobre foco, texto y estado, sin capturas ni verificación visual. Se ejecuta la suite existente para comprobar volumen y recuperación.

La prueba física del TV Box queda pendiente. Para realizarla con el siguiente parche real:

1. Instala 2.4 manualmente como actualización, sin desinstalar. Guarda canal y nivel de volumen conocidos.
2. En Configuración, abre las notas sin red, navega con el mando y vuelve a Versión con Atrás.
3. Con una versión posterior publicada, pulsa Buscar actualizaciones y autoriza el origen si Android lo pide. Confirma o cancela el instalador y exporta el log.
4. Comprueba que el APK se instala sobre la app y conserva canal y volumen; si se cancela, comprueba que no reaparece el instalador en ese arranque.
5. Prueba la ruta automática: deja descargar el siguiente parche, sal con Home y vuelve a BOBTV. Debe instalar antes de reproducir, con confirmación si el firmware la exige.
6. Interrumpe red durante una descarga y verifica que la versión anterior sigue disponible y que una nueva búsqueda recupera el parche. No hacen falta ADB, emulador ni capturas.

Validación local: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintRelease` y `:app:assembleRelease` completados correctamente; **190 pruebas, cero fallos, errores u omisiones**. Lint: **cero errores, ocho avisos** (compatibilidad conservada, herramientas, recursos existentes y cálculo conservador del espacio). `tools/release.py package` verifica firma, versión, SDK, modo release, notas dentro del APK y SHA-256 antes de preparar la entrega. Las pruebas JVM no certifican el instalador ni el firmware del dispositivo físico.

## Entrega publicada

- [Release v2.4, APK y notas](https://github.com/TylorDev/BOBTV/releases/tag/v2.4), publicada mediante [Actions](https://github.com/TylorDev/BOBTV/actions/runs/38507061248). Verificación y publicación finalizadas correctamente, incluida la eliminación del keystore temporal.
- APK `BOBTV-2.4-Android10.apk`: **12.835.603 bytes**, release no debuggable, versión 2.4/código 8 y certificado anterior comprobado mediante apksigner tras descargarlo de GitHub.
- SHA-256 publicado: `12bca12572271d7f6d36d013815830d145a40d8968e1ccb0918cdae78de90851`.
- `dist/` contiene exactamente los tres archivos publicados: APK, SHA-256 y `update.json`. Las entregas anteriores se conservan.
- Comprobado sin autenticación el endpoint `releases/latest/download/update.json`: coincide con el manifiesto descargado, el tamaño y hash del APK, y las notas dentro del ZIP coinciden con `releases/2.4.md`.
- La comprobación de `main` también pasó en [Actions](https://github.com/TylorDev/BOBTV/actions/runs/38507058399). Se corrigió la localización de sdkmanager en el runner antes de publicar la primera release; la etiqueta publicada referencia esa corrección.
