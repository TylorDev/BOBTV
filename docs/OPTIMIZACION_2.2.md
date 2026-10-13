# Políticas de optimización BOBTV 2.2

Base: af659b9, Kotlin/XML, Media3 1.11.1, API 29. Se conservan configuración, clipboard, fuentes alternativas, regiones, numeración y controles existentes.

## Estado y recuperación

AvailabilityState maneja UNKNOWN, ONLINE, SUSPECT y OFFLINE_CONFIRMED por fuente. Un negativo inconcluso no oculta un canal verificado. Un manifiesto inexistente/finalizado necesita dos probes separados por al menos 15 segundos y red operativa. Una reproducción positiva invalida tickets antiguos. Todas las fuentes deben estar confirmadas offline para ocultar un canal.

Unitel utiliza una política independiente con exactamente Santa Cruz, La Paz y Cochabamba. Sus entradas siguen seleccionables incluso offline, con aviso personalizado. El contador de disponibles, el inicio automático y Aceptar y cambiar usan evidencia positiva reciente (diez minutos). La navegación circular admite señales anteriormente verificadas sin caída confirmada; Unitel offline se elige manualmente.

TvPlayer emite categorías NETWORK, HTTP_TRANSIENT, ACCESS, MISSING_RESOURCE, PROTOCOL, CODEC, LIVE_WINDOW, ENDED, MEMORY, THERMAL, TIMEOUT y CANCELLED. Ninguna categoría de reproducción confirma por sí sola una caída del emisor.

La recuperación combina reintentos de carga (1, 2 y 5 segundos), alternativas y tres pasadas (2 y 5 segundos entre pasadas), nueve preparaciones o 120 segundos por ciclo y seis preparaciones automáticas por minuto. Hay un intento reservado por reconexión cada 30 segundos. Los cambios manuales pueden superar la cuota por minuto sin permitir ciclos ilimitados. Tras un ciclo agotado, cooldown de 60 segundos; máximo tres ciclos automáticos por diez minutos. Se renueva presupuesto con 120 segundos acumulados en cinco minutos y no más del 10% de rebuffer.

El watchdog mide 30 segundos sin avance útil de reproducción, no callbacks READY/BUFFERING. Se respeta supresión de audio y playWhenReady. Volver la red durante dos segundos recupera el mismo canal; no cambia a otra emisión. Cada selección, intento, catálogo y scan preserva tokens/revisiones que descartan callbacks antiguos.

## Calidad, memoria y directo

DefaultTrackSelector prefiere 1280×720 y 30 FPS, permitiendo exceder esas preferencias. En presión crítica prefiere 854×480 si existe, sin bloquear una pista superior única. No hay transcodificación ni extensiones de decodificación. Se registra el códec del sistema realmente inicializado; sus atributos hardware/software son declaraciones del firmware. Un fallo de ejecución permite un reintento local y excluye temporalmente ese códec para la fuente/MIME afectada.

Perfiles de muestras: normal 16 MiB, estabilización 24 MiB, reforzado 32 MiB, presión 8 MiB. Búfer mínimo/máximo: 5/20, 10/30, 15/40 y 3/10 segundos. Arranque 1,5 segundos y rebuffer de 3, 5, 8 y 3 segundos. Estos valores son objetivos iniciales; pueden bajar por ventana, bitrate o heap.

El presupuesto no supera el 25% del heap máximo. Expandir mantiene al menos max(16 MiB, 20% del heap) de reserva. Se estima bitrate de segmentos completos, sumando audio/video separados y reservando 20% de margen. Unknown no significa cero. El allocator tiene back buffer cero. El objetivo de bytes es un umbral, no un límite exacto de RSS/PSS ni de códecs. Una lectura de segmento comprueba periódicamente muestras superiores a 40 MiB o heap al 90% y aborta para una recuperación limitada.

SourceStability aumenta nivel tras dos cortes en 120 segundos o uno de cinco segundos; refuerza tras cuatro cortes en cinco minutos o 15 segundos de rebuffer en 120 segundos. Cortes inferiores a 500 ms, arranque y seeks no cuentan. El retraso aumenta en pasos de cinco segundos, como máximo uno cada 30 segundos, hasta 30 segundos o el mínimo superior del emisor. Cinco minutos reproducidos con menos del 1% de rebuffer reducen un nivel.

AdaptiveLoadControl y AdaptiveLiveController cambian política sin reemplazar el reproductor. La velocidad adaptativa admite 0,97–1,02 según los límites del origen; aumentar latencia gradualmente puede tardar varios minutos. Se busca posición histórica solo al preparar sin muestras útiles o al recuperar una posición expirada. No se hace seek durante reproducción útil para imponer el objetivo.

LiveWindowPolicy reserva dos segmentos o cinco segundos en el inicio de retención, y un segmento o dos segundos al extremo publicado; respeta min/max del emisor. Ventanas demasiado cortas desactivan la imposición del objetivo. Un retraso absoluto desconocido se acompaña de distancia relativa al extremo publicado. El buffer de arranque/rebuffer nunca espera más que la capacidad temporal efectiva. Retraso de 30 segundos no equivale a almacenar 30 segundos en RAM.

## Recursos y trabajo auxiliar

ResourceMonitor mide heap cada 15 segundos y PSS/memoria del sistema cada 60. PSS >100 MiB en tres muestras limita expansión; >128 MiB en dos muestras aplica presión crítica. Heap 75/85/90% activa limitación/crítica/suspensión. LowMemory y thermal MODERATE/SEVERE/CRITICAL también intervienen. Se requieren 120 segundos por debajo del nivel previo para recuperar. No se inventan grados de temperatura, no se fuerza GC y no se garantiza recuperación de cualquier OOM.

El scanner usa un trabajador, un único trabajo de ronda, máximo 30 fuentes/60 segundos, deadline de diez segundos por fuente, dos variantes examinadas, manifiesto de 1 MiB y muestra de 64 KiB. Un segmento reciente completo y una recarga limitada evitan carreras con el extremo. Timeout, cancelación, acceso rechazado o variantes no examinadas son inconclusos. Rondas nominales de dos minutos se posponen mientras el reproductor carga, se recupera o tiene presión. Admisión: 15 segundos de reproducción y cinco segundos de búfer. Una fuente seleccionada agotada puede comprobarse para recuperación acotada.

Catálogo/caché: 1 MiB, 999 canales, 16 fuentes por canal, 4096 en total, líneas 8 KiB. Se rechaza todo el catálogo si excede límites. Caché y temporal ocupan hasta 2 MiB; no hay video en disco ni logs persistidos. Las escrituras se verifican contra cancelación antes del reemplazo atómico y un fallo mantiene la lista anterior.

Home/salida cancela player, tareas, timers y listeners; destroy cierra executors. Los probes no crean reproductores. Los tests HTTP cierran sus servidores.

## Diagnóstico y límites físicos

SessionLog conserva 2000 eventos/256 KiB. Resúmenes de frames, rebuffer, recursos, bitrate, perfil, retraso y generaciones permiten diagnosticar sin ADB. Los contadores denominados stable excluyen los primeros cinco segundos desde el primer frame y periodos sin reproducción. El usuario debe además identificar fuente/red estable al evaluar el objetivo de menos del 1% de frames descartados.

Metas iniciales: PSS sostenido ~100 MiB, picos ~128 MiB, crecimiento inferior a 10 MiB entre medianas comparables después del calentamiento, respuesta del mando ~100 ms p95, arranque sin regresión superior a 500 ms o 20%. Se miden en el TV Box real; el emulador de 2 GB y las decodificaciones del PC no acreditan cumplimiento. Un servidor que no retiene segmentos suficientes o una red sostenidamente inferior al bitrate limitan cualquier recuperación.
