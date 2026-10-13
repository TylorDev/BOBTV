# Hello World para Android 13

Aplicación nativa en Kotlin con una pantalla que muestra **Hello World** en el centro. No requiere permisos ni conexión a Internet.

## Configuración

- Android mínimo: **Android 13 (API 33)**.
- SDK de compilación y destino: **API 33**.
- Identificador: `com.bobtv.helloworld`.
- Android Gradle Plugin: `9.0.1`, con Kotlin integrado.
- Gradle Wrapper: `9.1.0`.
- Java: Android Studio incluye un JDK compatible; el código se compila para Java 17.

## Abrir y ejecutar en Android Studio

1. Selecciona **Open** y abre la carpeta raíz de este proyecto.
2. Deja que Android Studio sincronice Gradle e instale los componentes del SDK que falten.
3. Para probar específicamente Android 13, crea un dispositivo en **Tools > Device Manager > Create Device** y selecciona una imagen de **Android 13 / API 33**.
4. Selecciona el dispositivo virtual y pulsa **Run** para ejecutar el módulo `app`.

También funciona en dispositivos con versiones posteriores a Android 13. El dispositivo virtual existente `Medium_Phone_API_37.0` utiliza una versión posterior; para comprobar el comportamiento en Android 13 selecciona una imagen API 33.

## Compilar desde PowerShell

Usa el Java incluido con Android Studio si no tienes `JAVA_HOME` configurado:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

Si no existe `local.properties`, Android Studio lo generará. Para compilar desde la terminal antes de abrir el IDE, configura `ANDROID_HOME` con la ruta de tu Android SDK. Estos ajustes son locales y no se guardan en Git.

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`.

Con un emulador encendido, puedes instalar la app con:

```powershell
.\gradlew.bat --no-daemon :app:installDebug
```

Las tareas de este proyecto deshabilitan el daemon persistente de Gradle y ejecutan el compilador Kotlin en el mismo proceso.

## Verificación realizada

- `:app:assembleDebug` y `:app:lintDebug`: completados correctamente.
- Android Lint: cero errores; tres advertencias sobre versiones más recientes de Android y Gradle. Se mantiene API 33 para esta app de Android 13 y la pareja compatible AGP 9.0.1 / Gradle 9.1.0.
- Metadatos del APK: SDK mínimo, de compilación y de destino **33**.
- Firma de depuración del APK: válida.

La validación se realizó mediante compilación e inspección del APK; no se ejecutó un emulador ni se hizo una revisión visual.

Referencias: [compilación con Gradle](https://developer.android.com/build/building-cmdline), [Kotlin integrado](https://developer.android.com/build/migrate-to-built-in-kotlin) y [dispositivos virtuales](https://developer.android.com/studio/run/managing-avds).
