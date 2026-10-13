# Validación de BOBTV 2.1

Validación realizada el 13 de octubre de 2026 mediante compilación, pruebas unitarias, lint y herramientas de inspección del APK. No se hicieron verificaciones visuales ni se inició un emulador o la app en un dispositivo físico.

## APK

- Versión **2.1**, código **4**.
- Identificador conservado: **com.bobtv.helloworld**.
- Mínimo API **29**, destino **33**, compilación **36**.
- Firma APK **v2** verificada con apksigner.
- Certificado SHA-256 conservado: **6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68**.
- Copia de esta tarea: **dist/BOBTV-2.1-Configuracion-Android10.apk**, 11.440.206 bytes.
- SHA-256 de esa copia: **13630ba95a6204bf66dba3df271b0288f350548c37399d993e8938adffa72204**.
- Se incluye el archivo de comprobación **.apk.sha256**.

La copia de esta tarea conserva el resultado verificado aunque otra tarea actualice posteriormente el APK general de dist.

## Comprobaciones

Se ejecutaron **:app:assembleDebug**, **:app:testDebugUnitTest** y **:app:lintDebug**, con resultado satisfactorio.

Las **44 pruebas de esta funcionalidad** incluyen los diez casos anteriores y 34 casos nuevos:

| Área | Casos |
|---|---:|
| M3U, selección inicial y caché anteriores | 9 |
| Calendario de reintentos anterior | 1 |
| Agrupación de fuentes y regiones | 4 |
| Disponibilidad y resultados antiguos | 4 |
| Comprobaciones concurrentes, cancelación y plazo | 4 |
| HLS, variantes, redirecciones, segmentos, claves y audio | 7 |
| Aceptación del fallo y siguiente canal activo | 3 |
| Pasadas de fuentes y actualización durante reproducción | 4 |
| Historial, redacción, tamaño y concurrencia de logs | 4 |
| HTTP real local, límites de lectura, rangos y cancelación | 4 |

Las pruebas HTTP usan servidores locales temporales; cada servidor cierra su socket y termina su hilo al finalizar el caso.

Lint terminó con **0 errores y 5 advertencias anteriores**: destino API 33, tamaño del banner vectorial, disponibilidad de otra versión de Gradle, orientación horizontal fija y contenedor raíz de foco. No aparecieron advertencias nuevas.

## Comportamiento implementado

- Configuración con una sola acción para copiar logs de la sesión al portapapeles.
- Fuentes alternativas para los siete identificadores oficiales compatibles, conservando las regiones.
- Catálogo completo en caché y lista visible limitada a canales con alguna fuente disponible.
- Comprobaciones sin otro reproductor, con dos tareas simultáneas, diez segundos por fuente y lecturas limitadas.
- Alternativas inmediatas y dos nuevas pasadas, con esperas de dos y cinco segundos.
- Fallo registrado antes del aviso; cambio al siguiente canal activo solo al aceptar.
- Listas vacías y recuperación de señales sin eliminar canales del catálogo.
- Invalidación de resultados antiguos y cancelación al detener la actividad.

La disponibilidad se comprueba desde el dispositivo donde corre la app. Esta validación no afirma haber reproducido todas las señales actuales ni haber probado visualmente el mando o el portapapeles en el TV box. Las nuevas URLs las incorporará el usuario siguiendo [FUENTES.md](FUENTES.md).
