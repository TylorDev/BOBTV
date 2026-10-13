# Validación manual en el TV Box — BOBTV 2.2

Esta guía no acredita pruebas ya realizadas. La reproducción de RED UNO confirmada por el usuario el 13 de octubre corresponde al historial anterior; las nuevas fuentes 1080p y el perfil 2.2 requieren comprobación física.

Equipo reportado: Allwinner H313/H616, cuatro Cortex-A53 hasta 1296 MHz, sistema de 32 bits, ABI armeabi-v7a/armeabi, 1 GB instalado (970 MB mostrados), 236 MB disponibles en una captura. Almacenamiento mostrado: 4063 MB total, 1975 MB libres. El nombre comercial y las declaraciones del firmware no prueban capacidades de decodificación. Los ~73 MiB PSS anteriores eran de un emulador con 2 GB.

## Preparación y logs

1. Transfiere **dist/BOBTV-2.2-Android10.apk** con AnyDesk e instala como actualización, sin desinstalar.
2. Abre BOBTV. No hace falta ADB ni depuración inalámbrica.
3. Después de cada bloque usa **Menú → Exportar logs al portapapeles** y pega el texto en el chat, indicando canal, duración, tipo de conexión, si hubo imagen/sonido y qué observaste.
4. Exporta cada 10–15 minutos en sesiones largas: el historial está limitado a 2000 eventos/256 KiB. Los resúmenes conservan contadores acumulados.
5. Anota fuentes de alto bitrate/FPS, audio MP2 y temperatura solo si los logs lo indican; no deduzcas capacidad del nombre de la caja.

## Bloques de prueba

| Bloque | Duración / repeticiones | Qué comprobar |
|---|---|---|
| Fuente estable | 30 min | Imagen/sonido continuos, arranque habitual, latencia normal, mando ágil y memoria sin crecimiento |
| Unitel con cortes | 30 min | Conserva número y selección; perfiles cambian, objetivo de retraso aumenta sin resets continuos |
| Única fuente superior | 15 min | Se intenta en calidad original; registrar códec, FPS/bitrate disponibles y límites de recursos |
| Red Uno MP2 y alternativa AAC | 10 min por fuente disponible | Sonido real; fallback si el códec local falla, sin ocultar la emisión por ese motivo |
| Red perdida | Cortes de 10, 30 y 90 s | Recupera el mismo canal al volver; no cambia a otro solo |
| Mando y cambios | 50 cambios | Flechas circulares, números/ceros iniciales/OK/Atrás, sin saltos tardíos ni pérdida de foco |
| Home / volver | 10 ciclos | Cesa video/sonido fuera de la app y recupera selección al volver |
| Sesión prolongada | 60 min | Recursos, temperatura declarada, frames, latencia y continuidad |

Para cortar la red, usa el router o desactiva la conexión del TV Box manualmente. Conserva una sesión con el mismo canal durante todo el corte. En otro ensayo elige un canal distinto durante la desconexión: al volver debe recuperar el nuevo.

Comprueba que Unitel Santa Cruz, La Paz y Cochabamba aparecen aun sin señal; pueden elegirse por lista/número, muestran estado real y el aviso personalizado. Si solo quedan Unitel sin señal confirmada, no deben empezar a alternarse automáticamente. Red Uno y Bolivisión no tienen esa excepción.

En avisos de fallo, izquierda/derecha y OK eligen Reintentar o Aceptar y cambiar; Unitel ofrece Elegir otro canal. Atrás abre la lista. Configuración y confirmación de salida deben conservar foco y bloquear entrada numérica. Volumen sigue siendo del sistema; este trabajo no implementa la escala 0–100.

Si el catálogo cambia de orden mientras escribes, la entrada se cancela. Ocultar canales no cambia números. Si desaparece el canal actual, puede seguir viéndose con el rótulo Fuera de la lista. Una actualización o un probe no debe cortar una emisión estable.

## Interpretación

- Retraso del directo, segundos de búfer y bytes de muestras son medidas distintas.
- El objetivo de 30 segundos depende de la retención HLS. LIVE_WINDOW_LIMIT o LIVE_WINDOW_TOO_SHORT explica un objetivo menor o no imponible.
- Un retraso gradual puede tardar varios minutos. LiveActual desconocido no es cero; relativeToPublishedEnd es una estimación diferente.
- Que una fuente tenga manifiesto/segmentos accesibles no garantiza audio/video ni compatibilidad del códec.
- No compare resultados de una red o fuente inestable con la meta de frames de una señal estable.
- Los contadores stable excluyen incorporación inicial y periodos sin reproducción; revisar stableDropped/(stableRendered+stableDropped).
- PSS puede omitir memoria de gráficos y servicios de decodificación. Thermal es un nivel del sistema, no grados medidos.
- DispatchP95Ms mide atención dentro de la app; la observación manual sigue siendo necesaria para la sensación del mando.

Metas iniciales para evaluar: PSS sostenido ~100 MiB, picos ~128 MiB; ausencia de crecimiento persistente (diferencia menor a 10 MiB entre medianas comparables después del calentamiento); atención del mando ~100 ms p95; frames descartados <1% con fuente/red estables. Arranque sin regresión mayor a 500 ms o 20%, el mayor de ambos. Son objetivos, no garantías.

Si aparece suspensión térmica, espera a que el equipo se recupere; no tapes su ventilación. Una fuente fija superior o una conexión sostenidamente inferior a su bitrate puede superar lo que esta caja reproduce con fluidez. Exporta los logs antes de cerrar la app, porque son de la sesión actual.
