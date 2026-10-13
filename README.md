# Hello World para Android 10 y versiones posteriores

Aplicación nativa en Kotlin con una pantalla que muestra **Hello World** en el centro. No requiere permisos ni conexión a Internet.

## Configuración

- Android mínimo: **Android 10 (API 29)**.
- SDK de compilación y destino: **API 33 (Android 13)**. Esto no impide instalar la app desde Android 10.
- Versión de la app: **1.1**, código de versión **2**.
- Identificador: `com.bobtv.helloworld`.
- Android Gradle Plugin: `9.0.1`, con Kotlin integrado.
- Gradle Wrapper: `9.1.0`.
- Java: Android Studio incluye un JDK compatible; el código se compila para Java 17.

## Abrir y ejecutar en Android Studio

1. Selecciona **Open** y abre la carpeta raíz de este proyecto.
2. Deja que Android Studio sincronice Gradle e instale los componentes del SDK que falten.
3. Para probar la versión mínima compatible, crea un dispositivo en **Tools > Device Manager > Create Device** y selecciona una imagen de **Android 10 / API 29**. También puedes elegir **Android 13 / API 33**.
4. Selecciona el dispositivo virtual y pulsa **Run** para ejecutar el módulo `app`.

El dispositivo de prueba mostrado en la captura usa Android 10 / API 29. La versión anterior de la app exigía API 33 y Android rechazaba su instalación. Esta versión reduce el mínimo a API 29 y mantiene compatibilidad con Android 13 y versiones posteriores.

El dispositivo virtual existente `Medium_Phone_API_37.0` utiliza una versión posterior; para comprobar el comportamiento en Android 10 selecciona una imagen API 29.

## Compilar desde PowerShell

Usa el Java incluido con Android Studio si no tienes `JAVA_HOME` configurado:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

Si no existe `local.properties`, Android Studio lo generará. Para compilar desde la terminal antes de abrir el IDE, configura `ANDROID_HOME` con la ruta de tu Android SDK. Estos ajustes son locales y no se guardan en Git.

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`.

Para esta entrega también se dejó una copia verificada con el nombre `app/build/outputs/apk/debug/HelloWorld-Android10-v1.1-debug.apk`, para distinguirla del APK anterior.

Con un emulador encendido, puedes instalar la app con:

```powershell
.\gradlew.bat --no-daemon :app:installDebug
```

Las tareas de este proyecto deshabilitan el daemon persistente de Gradle y ejecutan el compilador Kotlin en el mismo proceso.

## Instalar en el dispositivo con AnyDesk

1. Transfiere el APK recién generado a la carpeta **Descargas** del dispositivo mediante AnyDesk.
2. En Android, abre el gestor de archivos y selecciona el APK.
3. Si Android lo solicita, habilita **Instalar aplicaciones desconocidas** para ese gestor de archivos.
4. Pulsa **Instalar** y después **Abrir**.

El APK debe indicar versión **1.1** y mínimo Android 10. Usa el nuevo archivo y evita reutilizar la descarga anterior, que exigía Android 13.

## Verificación realizada

- `:app:assembleDebug` y `:app:lintDebug`: completados correctamente.
- Android Lint: cero errores; tres advertencias sobre versiones más recientes de Android y Gradle. Se mantiene el SDK de destino API 33 y la pareja compatible AGP 9.0.1 / Gradle 9.1.0.
- Metadatos del APK: SDK mínimo **29** y SDK de compilación y destino **33**; versión **1.1** / código **2**.
- Firma de depuración del APK: válida.

La validación se realizó mediante compilación e inspección del APK; no se ejecutó un emulador ni se hizo una revisión visual. La instalación en el dispositivo físico queda pendiente de realizar desde AnyDesk.

Referencias: [SDK mínimo y de destino](https://developer.android.com/guide/topics/manifest/uses-sdk-element), [compilación con Gradle](https://developer.android.com/build/building-cmdline), [Kotlin integrado](https://developer.android.com/build/migrate-to-built-in-kotlin), [dispositivos virtuales](https://developer.android.com/studio/run/managing-avds) y [transferencia de archivos con AnyDesk](https://support.anydesk.com/docs/es/file-manager-and-file-transfer).
