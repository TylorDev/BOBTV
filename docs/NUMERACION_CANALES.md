# Numeración y selección de canales

Cada canal recibe un número según su posición en el catálogo M3U completo: `01`, `02`, …, `99`, `100`, …, `999`. Si un canal se oculta porque no tiene señal, su número queda reservado y los demás conservan el suyo. Agregar otra fuente al mismo canal no crea un número adicional.

Reordenar o quitar canales del catálogo cambia los números posteriores. La app sigue recordando el último canal por su `tvg-id`, no por su número. Un catálogo con más de 999 canales distintos se rechaza y se conserva la última lista válida.

## Elegir con el control

Escribe el número con los botones `0`–`9` del mando o del teclado numérico, tanto viendo TV como con la lista abierta. El número escrito aparece arriba a la derecha sin mover el foco de la lista.

- `5`, `05` y `005` eligen el mismo canal.
- Con uno o dos dígitos, el cambio ocurre tras 1,5 segundos sin escribir. Puedes confirmar antes pulsando **OK**.
- El tercer dígito confirma inmediatamente. Un cuarto dígito inicia una nueva selección.
- **Atrás** cancela una entrada pendiente sin cerrar la lista ni solicitar salir.
- Las flechas y **CH+ / CH−** cancelan la entrada y mantienen su función habitual.
- La entrada numérica está desactivada en Configuración y en la confirmación de salida.

Si el número no existe, aparece «Canal XX no existe». Si corresponde a un canal oculto por falta de señal, aparece «Canal XX no disponible». Estos avisos duran dos segundos y no cambian la reproducción ni cierran la lista. `0` y `000` no corresponden a ningún canal.

En 2.2, un fallo transitorio o el agotamiento de reintentos no oculta un canal verificado. Unitel Santa Cruz, La Paz y Cochabamba permanecen seleccionables por lista y número aun confirmados offline, con su estado real. No reciben un número nuevo ni se consideran online por estar visibles.

## Canal actual y recorrido circular

Cada selección muestra durante cinco segundos el número y el nombre publicados en el catálogo, por ejemplo `05 - RED UNO Santa Cruz`. El aviso se muestra también durante la carga o un fallo de reproducción.

Al ver TV, **↑ / CH+** eligen el siguiente canal disponible y **↓ / CH−** el anterior. Después del último se vuelve al primero; antes del primero se vuelve al último. Dentro de la lista, las flechas mueven la selección y **OK** la confirma.

La navegación de 2.2 también conserva canales previamente verificados durante incertidumbre o cortes. Excluye Unitel confirmado offline del zapping; sigue disponible en lista y por número. Aceptar y cambiar utiliza únicamente emisiones con evidencia positiva reciente, sin bucles entre canales sin señal.

Si se elimina del catálogo el canal que estás viendo, la reproducción continúa y el aviso indica «NOMBRE · Fuera de la lista», sin asignarle el número de otro canal. Al actualizar la disponibilidad se mantienen los números del catálogo; si cambia su orden mientras escribes, la entrada pendiente se cancela.

## Comprobaciones automatizadas

Las pruebas JVM cubren la numeración completa, los huecos por disponibilidad, los cambios de orden o nombre, los límites del catálogo y su caché, el recorrido circular, las teclas numéricas, las entradas con ceros iniciales y los números inexistentes o no disponibles. La temporización se comprueba con tiempo simulado, incluidos los callbacks cancelados y los efectos de mantener presionada una tecla.

La validación de entrega incluye pruebas unitarias, lint y compilación del APK de depuración. No se realizan verificaciones visuales.

## Entrega verificada

El 13 de octubre de 2026 se completaron las 77 pruebas JVM, incluidos 33 casos de numeración y selección numérica. Lint terminó con cero errores y las cinco advertencias existentes. La compilación del APK de depuración fue satisfactoria.

La entrega con esta mejora está en `dist/BOBTV-2.1-Numeracion-Android10.apk`, con su archivo `.sha256`. También se actualizó `dist/BOBTV-2.1-Android10.apk`. Se conserva la versión 2.1, código 4, el identificador `com.bobtv.helloworld` y Android 10 / API 29 como mínimo.

La firma APK v2 se verificó con el certificado anterior, SHA-256 `6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68`, para permitir la actualización de las instalaciones existentes. Todos los procesos de Gradle iniciados durante esta tarea terminaron.
