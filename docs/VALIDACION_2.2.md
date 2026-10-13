# Validación de BOBTV 2.2

Entrega generada el 13 de octubre de 2026 desde la base af659b9, conservando Kotlin/XML y Media3 1.11.1. Verificación automatizada completada; pruebas físicas pendientes en el TV Box.

## APK

- Entrega: **dist/BOBTV-2.2-Android10.apk**.
- Versión **2.2**, código **5**.
- Identificador **com.bobtv.helloworld**.
- Mínimo API **29**, destino **33**, compilación **36**.
- Tamaño **11.745.797 bytes**.
- SHA-256: **ac2027180462ce47ec8b4688dbd88711bbd3b7795758952f895a09a80fc04de7**.
- Archivo de comprobación **dist/BOBTV-2.2-Android10.apk.sha256**.
- Firma APK **v2** verificada con apksigner.
- Certificado SHA-256 conservado: **6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68**.

Puede instalarse como actualización sin desinstalar. Las preferencias y el M3U en caché siguen siendo compatibles. No se añadieron bibliotecas nativas externas de códecs ni restricciones de ABI.

## Comprobaciones completadas

Se ejecutaron **:app:assembleDebug**, **:app:testDebugUnitTest** y **:app:lintDebug**, con resultado satisfactorio.

**113 pruebas JVM, 21 suites, cero fallos y cero errores.** Se conservan los casos anteriores, adaptando las expectativas de ocultamiento inmediato y concurrencia a las políticas nuevas. La prueba de duplicados conserva 1000 entradas repetidas, con hasta 16 URLs únicas, para respetar el nuevo límite por canal.

Cobertura ampliada: disponibilidad concluyente/inconclusa, visibilidad exclusiva de Unitel, fallos locales sin atribuir caída del emisor, revisión y rotación de tickets, ventana HLS corta/larga y límites del origen, arranque limitado por bytes, headroom de heap, cortes recurrentes sin 30 segundos seguidos, recuperación acumulada, cuotas/cooldown, Retry-After, generaciones en reintento manual/salida, presión de recursos, límites de catálogo/caché, cancelación de escrituras, redacción de tokens y rondas acotadas.

Lint: **0 errores y las 5 advertencias anteriores** — target API 33, banner vectorial grande, nueva versión de Gradle disponible, orientación horizontal fija y contenedor raíz de foco. La palabra española «momento» usa una exclusión puntual del detector de erratas en inglés. Las anotaciones de Media3 se declaran explícitamente y se conserva la versión fijada.

Las herramientas del SDK emitieron avisos no bloqueantes sobre métricas de Android, versión de XML del SDK y acceso nativo de Java al verificar la firma. No se modificó el entorno externo para ocultarlos.

Los servidores HTTP de pruebas cerraron sus sockets/hilos. Gradle se ejecutó sin daemon persistente; al concluir no quedaron procesos Java de la tarea. No se inició emulador, ADB, servidor de desarrollo ni aplicación para verificación visual.

## Pendiente físico

Esta validación no confirma PSS, fluidez, hardware real de decodificación, audio MP2, temperatura, mando/foco, reconexión ni clipboard en la caja física. El usuario realizará [PRUEBA_TV_BOX.md](PRUEBA_TV_BOX.md) y pegará logs.

Las metas iniciales son PSS sostenido próximo a 100 MiB, picos próximos a 128 MiB, ausencia de crecimiento sostenido, atención del mando próxima a 100 ms y frames descartados inferiores al 1% en fuente/red estables. No son garantías del equipo de 1 GB. Un retraso de 30 segundos requiere retención suficiente y los logs distinguen objetivo, medida real y estimación relativa.

Consultar [OPTIMIZACION_2.2.md](OPTIMIZACION_2.2.md) para políticas, límites y diagnóstico. El plan de volumen 0–100 no se incluyó.

## Recuperación de comportamiento anterior

Se conserva sin modificar **dist/BOBTV-2.1-Numeracion-Android10.apk**, 11.445.638 bytes, SHA-256 **562b1edb0252190860f5150b9807e8b88ce8b4a5cbd295f0493a3c81b19184a6**.

Android normalmente rechaza instalar código 4 sobre código 5. Si hace falta recuperar 2.1 sin perder datos:

1. Preparar una copia aislada de la base **af659b9**, preservando esta entrega y sus commits.
2. Compilar ese comportamiento con código **6 o superior al instalado**, nombre que indique recuperación y el mismo certificado/appId.
3. Verificar firma, metadatos y SHA-256.
4. Instalar como actualización, sin desinstalar ni borrar datos.
5. Registrar el motivo y comprobar manualmente el canal afectado.

No se generó un APK adicional de recuperación porque no se ha solicitado volver atrás. No se modificó ni publicó el repositorio remoto de canales.
