# Fuentes IPTV de Unitel y Red Uno verificadas el 13 de octubre de 2026

Se revisaron **32 enlaces distintos** encontrados en repositorios, gists y foros, además del sitio técnico de Red Uno. Se admitieron **tres URLs nuevas** tras descargar segmentos reales y decodificar audio y video. El catálogo público queda con **13 canales y 16 fuentes**, agrupadas por los identificadores existentes.

## Fuentes incorporadas

| Señal | URL pública | Video | Audio | Fotogramas de video / audio de la confirmación | Secuencia HLS |
|---|---|---|---|---:|---|
| UNITEL Bolivia | `http://190.181.61.93:8000/play/1909/index.m3u8` | H.264, 1920×1080 | AAC, 48 kHz, estéreo | 1799 / 1408 | 424 → 435 |
| RED UNO La Paz | `http://190.181.61.93:8000/play/a003/index.m3u8` | H.264, 1920×1080 | MP2, 48 kHz, estéreo | 899 / 1251 | 289 → 301 |
| RED UNO Santa Cruz | `http://190.181.61.93:8000/play/a002/index.m3u8` | H.264, 1920×1080 | MP2, 48 kHz, estéreo | 899 / 1251 | 290 → 302 |

Las tres URLs constan en [iptv-org/iptv, revisión 17ef66a](https://github.com/iptv-org/iptv/blob/17ef66a792e4556f352bf886042add62d37dd0e7/streams/bo.m3u). La identidad de las emisiones se confirmó también en fotogramas con los logotipos de UNITEL y Red Uno. Los nombres regionales proceden de ese catálogo; no se verificó una programación regional exclusiva.

## Comprobación realizada

Las pruebas se realizaron desde la red del equipo de desarrollo entre las **17:46 y las 17:58**, hora de São Paulo (UTC−3). Se utilizó FFmpeg/FFprobe 9.0.1, el agente público `BOBTV/2.0`, conexiones HTTP/HTTPS normales y tiempos de espera limitados.

1. Resolver el manifiesto maestro y su variante, comprobar que siguen en directo y descargar un segmento MPEG-TS real.
2. Identificar las pistas con FFprobe y decodificar una primera muestra de unos 30 segundos de audio y video.
3. Abrir otra sesión y contar fotogramas de video y bloques de audio ya decodificados. Las tres confirmaciones finales contienen aproximadamente 30 segundos efectivos de cada medio; los hashes de fotogramas y audio varían.
4. Consultar de nuevo el HLS y confirmar que cambian la secuencia y los segmentos; verificar que el contenido pertenece al canal indicado.

Para la confirmación de Red Uno se calculó SHA-256 de los fotogramas originales. Las muestras anteriores de Unitel que calculaban hashes de cada fotograma 1080p registraron saltos y una lectura truncada. La comprobación final decodificó el video original y redujo la imagen a 320 píxeles solo para calcular los hashes MD5: produjo 1.799 fotogramas de video y 1.408 bloques AAC, equivalentes a 30 segundos completos, con salida satisfactoria. Esto sugiere que la carga de la comprobación influyó en los saltos; no descarta incidencias del emisor. Se registraron avisos de sincronización H.264 al incorporarse al directo y un aviso de paquete corrupto en Unitel. No se califica ninguna fuente como estable las 24 horas.

Las pruebas confirman descarga y decodificación en este equipo, **sin prueba física en el TV box**. Las fuentes nuevas de Red Uno usan audio MP2; la reproducción de ese audio en BOBTV requiere que el dispositivo disponga de un decodificador compatible. La fuente previa de RED UNO con audio AAC permanece en primer lugar. Las tres fuentes nuevas comparten servidor, por lo que no ofrecen redundancia frente a la caída de ese servidor.

El [informe JSON](https://github.com/TylorDev/bobtv-canales/blob/main/verificacion/fuentes-2026-10-13.json) conserva URLs, horarios, códecs, contadores, resultados descartados y validación del catálogo. No se agregaron direcciones con usuarios, contraseñas ni firmas temporales.

## Orden y compatibilidad

- **UNITEL Santa Cruz**: la nueva URL ocupa el primer lugar de `UnitelSantaCruz.bo@SD`; la URL anterior de Mediastream se conserva como alternativa. El identificador y el nombre originales se mantienen.
- **RED UNO Santa Cruz**: la nueva URL ocupa el primer lugar de `RedUnoSantaCruz.bo@SD`; se conserva la fuente HTTP anterior como alternativa.
- **RED UNO**: permanece primero el enlace HTTPS previo con AAC; la fuente nueva catalogada como La Paz se agrega bajo `RedUno.bo@SD`.

BOBTV 2.0 usa la primera entrada de cada `tvg-id`. El lector de fuentes múltiples de BOBTV 2.1 agrupa las entradas repetidas de estos canales y puede utilizar las alternativas. Repetir el identificador agrega una fuente al mismo canal y conserva la selección guardada. El resto de los canales mantiene sus fuentes y su orden.

## Búsqueda y descartes

Se consultaron [iptv-org/iptv](https://github.com/iptv-org/iptv), [12carloshc/canales-de-bolivia-IPTV](https://github.com/12carloshc/canales-de-bolivia-IPTV), [josezambrana](https://gist.github.com/josezambrana/d2a0cc38fc24eea1aaecf5e676360dfa), [fiksani](https://gist.github.com/fiksani/9827055b019258cc5f7988c483e229cf), [elfenderes](https://gist.github.com/elfenderes/16ec4ac5b1dfd9d59d02e3f4d9a82d49), [RaulBravo343/2023.m3u](https://github.com/RaulBravo343/2023.m3u), [akarickyyy](https://gist.github.com/akarickyyy/70bc38fb1245962f482e893fb1a333ef), [Sultanovic](https://sultanovic.info/forum/25-2339-92), [Capa9](https://www.capa9.net/temas/mi-propia-gu%C3%ADa-epg.1125972/page-5), [TDTChannels](https://www.tdtchannels.com/catalogo/television/RedUnodeBolivia/) y [Red Uno Digital](https://digital.reduno.com.bo/). PiknikTV devolvió una página de carga sin enlaces utilizables. También se revisaron discusiones de Reddit sobre televisión boliviana; no aportaron una URL HLS adicional comprobable.

De los 32 enlaces probados, **26 fallaron**, dos ya existían y uno correspondía a Unitel de Perú. Los tres restantes se incorporaron. Los principales descartes fueron:

| Familia de enlaces | Resultado observado |
|---|---|
| Mediastream: las tres URLs originales de Unitel | HTTP 403 |
| BozzTV: tvjoaquiniana26; Unitel La Paz en 190.60.39.195 | HTTP 404 |
| Red Uno en 15.204.246.24 y alternativa a005 en 190.181.18.82 | HTTP 404 |
| Red Uno: HUB, HUB1 y muxredunosc en tucableip.com | HTTP 404 |
| Dailymotion firmado en la lista de 12carloshc | HTTP 403 |
| Dailymotion antiguo de RaulBravo343 | DNS sin resolver |
| Servidores digital.com.bo y 136.243.3.70 | Tiempo de espera agotado |
| Señales de Sultanovic en 159.89.238.35 y 181.204.39.66; 200.58.160.67 | Tiempo de espera agotado |
| agenciastreaming.com/uniteltv | Homónimo de Perú, excluido; además no completó la muestra de decodificación |

La disponibilidad puede cambiar después de la comprobación. Para recibir el catálogo publicado, abre la lista de BOBTV y pulsa **Actualizar canales**; GitHub puede tardar unos minutos en propagar el archivo.

## Publicación

Las fuentes y la evidencia se publicaron en `main` de [TylorDev/bobtv-canales](https://github.com/TylorDev/bobtv-canales), en el [commit a776015](https://github.com/TylorDev/bobtv-canales/commit/a776015a8c07fde5e64a56d48d853a0c0ce5f88e). La actualización se recibe desde el catálogo remoto de la app.
