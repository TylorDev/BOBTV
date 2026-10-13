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

La app comprueba las señales desde la red del TV box al abrir, al actualizar y cada dos minutos mientras está activa. Valida el manifiesto y una muestra de datos de un segmento, con dos comprobaciones simultáneas y un límite de diez segundos por fuente.

Solo aparecen canales con alguna fuente disponible. Los canales ocultos permanecen en el catálogo y reaparecen si recuperan señal. La reproducción puede fallar entre comprobaciones; el reproductor entonces cambia de fuente y permite dos nuevas pasadas, con esperas de dos y cinco segundos.

Si se agotan las fuentes, la app registra los errores y muestra **Aceptar y cambiar**. Al aceptar abre el siguiente canal en línea; si ninguno está disponible muestra la lista vacía con sus acciones.

Para copiar el diagnóstico pulsa **Menú → Exportar logs al portapapeles**, o **OK → Configuración → Exportar logs al portapapeles**. Los logs incluyen solo la sesión actual, hasta 2.000 eventos y 256 KiB. Las credenciales y los parámetros de las URLs se ocultan.
