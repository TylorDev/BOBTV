# Fuentes alternativas de Unitel, Red Uno y Bolivisión

Las fuentes alternativas requieren **BOBTV 2.1 o posterior**. Se administran en el mismo [canales.m3u de GitHub](https://github.com/TylorDev/bobtv-canales/blob/main/canales.m3u), sin recompilar ni reinstalar la app.

Cada fuente es una URL directa de transmisión **HLS HTTP o HTTPS**, normalmente terminada en .m3u8. Mantén **#EXTM3U** al principio y guarda el archivo como UTF-8.

## Identificadores compatibles

Repite exactamente el **tvg-id** del canal para añadir otra fuente. Las variantes regionales permanecen separadas:

| Canal | tvg-id |
|---|---|
| Unitel Santa Cruz | UnitelSantaCruz.bo@SD |
| Unitel La Paz | UnitelLaPaz.bo@SD |
| Unitel Cochabamba | UnitelCochabamba.bo@SD |
| Red Uno | RedUno.bo@SD |
| Red Uno Santa Cruz | RedUnoSantaCruz.bo@SD |
| Bolivisión Bolivia | Bolivision.bo@SD |
| Bolivisión Santa Cruz | BolivisionSantaCruz.bo@SD |

La app reúne las URLs del mismo identificador en una sola entrada visible y elimina URLs repetidas. El nombre, grupo y posición del canal corresponden a su primera entrada. Otros identificadores siguen usando su primera URL.

## Ejemplo: dos fuentes por canal

**Las direcciones siguientes son ejemplos; reemplázalas por tus URLs reales. No las añadas a la lista publicada sin sustituirlas.**

~~~m3u
#EXTM3U
#EXTINF:-1 tvg-id="UnitelSantaCruz.bo@SD" group-title="Bolivia | Nacionales",UNITEL Santa Cruz
https://principal.example/unitel-sc/index.m3u8
#EXTINF:-1 tvg-id="UnitelSantaCruz.bo@SD" group-title="Bolivia | Nacionales",UNITEL Santa Cruz
https://alternativa.example/unitel-sc/index.m3u8

#EXTINF:-1 tvg-id="RedUno.bo@SD" group-title="Bolivia | Nacionales",RED UNO
https://principal.example/red-uno/index.m3u8
#EXTINF:-1 tvg-id="RedUno.bo@SD" group-title="Bolivia | Nacionales",RED UNO
https://alternativa.example/red-uno/index.m3u8

#EXTINF:-1 tvg-id="Bolivision.bo@SD" group-title="Bolivia | Nacionales",BOLIVISIÓN Bolivia
https://principal.example/bolivision/index.m3u8
#EXTINF:-1 tvg-id="Bolivision.bo@SD" group-title="Bolivia | Nacionales",BOLIVISIÓN Bolivia
https://alternativa.example/bolivision/index.m3u8
~~~

Para las demás regiones, usa su identificador de la tabla y las URLs de esa misma señal regional. Por ejemplo, una alternativa de Unitel La Paz debe repetir **UnitelLaPaz.bo@SD**.

## Agregar, ordenar, sustituir y eliminar

1. Abre el archivo en GitHub y pulsa el lápiz.
2. Para agregar una fuente, añade un bloque de dos líneas: **#EXTINF** con el identificador existente y su nueva URL.
3. Pon las fuentes preferidas antes que las alternativas. Se comienza con la primera confirmada disponible; si falla, se recorren las demás.
4. Para sustituir una fuente, modifica solo su URL. Para eliminarla, borra únicamente ese bloque de dos líneas. Para quitar el canal completo, elimina todos sus bloques.
5. Para renombrar un canal, conserva su **tvg-id** y cambia el nombre de la primera entrada; mantener el mismo nombre en sus alternativas facilita la edición.
6. Guarda con **Commit changes** en **main**.
7. En BOBTV pulsa **OK → Actualizar canales**. La actualización conserva el video actual y actualiza sus alternativas.

La lista también se descarga al abrir y cada 30 minutos mientras la app está activa. GitHub puede tardar unos minutos en servir los cambios.

## Disponibilidad y diagnóstico

BOBTV 2.2 comprueba las señales desde la red del TV box. Valida manifiesto y muestra de segmento con un trabajador, límite de diez segundos por fuente y rondas de hasta 30 fuentes/60 segundos. Los intervalos nominales de dos minutos se posponen durante arranque, cortes y presión de recursos.

Los canales verificados permanecen seleccionables durante errores transitorios, incluso si se agotan reintentos. Ocultarlos exige indisponibilidad concluyente de todas sus fuentes, repetida con red operativa. Los tres IDs de Unitel de esta guía siempre permanecen visibles, sin contarse como online cuando están offline. Red Uno y Bolivisión no tienen esa excepción. Los canales ocultos siguen en el catálogo y pueden recuperar disponibilidad.

Se conservan alternativas y tres pasadas con esperas de dos y cinco segundos; 2.2 añade límites comunes de tiempo/preparaciones y cooldown. Los fallos ofrecen Reintentar y Aceptar y cambiar; Unitel permite Elegir otro canal. Solo se eligen destinos con evidencia positiva reciente. Un error local del códec no confirma caída del emisor. Al volver la conexión se recupera automáticamente el canal seleccionado.

El catálogo admite 16 URLs por canal, 4096 fuentes totales, 999 canales, 1 MiB y líneas de 8 KiB. Exceder un límite rechaza la lista completa y conserva la caché anterior. Las fuentes nuevas de Red Uno con MP2 requieren validación física de ese audio; la alternativa AAC existente permanece disponible.

Para copiar el diagnóstico pulsa **Menú → Exportar logs al portapapeles**, o **OK → Configuración → Exportar logs al portapapeles**. Los logs incluyen solo la sesión actual, hasta 2.000 eventos y 256 KiB. Las credenciales y los parámetros de las URLs se ocultan.
