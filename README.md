# Handheld Alert

App Android que se superpone al navegador (Dolphin o Chrome) del handheld y
muestra una alerta grande en el **75% superior** de la pantalla (el 25% de
abajo queda libre para seguir completando los campos de la transacción,
ej. Tipo/Número/Nro. Doc/Id. Clie/Cierre/Ordenar no leídos en ZV29):

- **Roja** (`#8C1F23`) cuando aparece `error.gif`.
- **Oliva/mostaza** (`#B39B2E`) cuando aparece `warning.gif`.
- **Verde** (`#215A1C`) cuando aparece `enter.gif`, mostrando el número de
  ruta leído de la pantalla (ej: `R 12345`).

Los tres colores están medidos directamente de los gifs reales de SAP
ITSmobile (`/sap/public/bc/its/mimes/itsmobile/99/images/all/`), no
inventados — ver `ContextColor` en `OverlayAlertManager.kt`.

## Cómo funciona (resumen técnico)

No es posible interceptar de forma confiable las requests de red de otra app
(Dolphin/Chrome) sin instalar un certificado o correr un stack TCP/IP propio
vía VpnService — así que la detección se hace por otras dos vías, que en la
práctica cubren lo mismo:

1. **Color del ícono** (`ScreenWatchService` + `IconColorAnalyzer`): captura
   la pantalla con `MediaProjection` cada ~700ms, recorta la zona calibrada
   (donde sale error.gif / warning.gif / enter.gif — siempre pegados al
   margen izquierdo, en la primera fila debajo del título) y clasifica el
   color dominante entre los tres estados.
2. **Texto / mensaje** (`RouteAccessibilityService` + `ScreenTextHolder`):
   lee el árbol de accesibilidad del navegador cada vez que cambia el
   contenido. Para el estado de éxito busca el patrón `R <número>`; para
   error/aviso usa el texto completo leído (ej. "El campo Almacén es de
   ingreso obligatorio").

Cuando el color detectado se estabiliza (2 lecturas seguidas iguales, para
evitar parpadeos), `OverlayAlertManager` muestra el overlay ocupando el 75%
superior de la pantalla (`Gravity.TOP`, alto = `OVERLAY_HEIGHT_FRACTION *
displayMetrics.heightPixels`), rojo/oliva/verde según el estado, con el
mensaje. El 25% inferior de la pantalla real queda sin cubrir — los toques
ahí van directo al navegador.

## Compilar el APK sin instalar nada (PC corporativa sin permisos de admin)

Este repo incluye `.github/workflows/build-apk.yml`: un flujo de GitHub
Actions que compila el APK en la nube (gratis) — solo necesitás un navegador
y una cuenta de GitHub, nada que instalar en tu PC.

1. **Creá una cuenta de GitHub** si no tenés (github.com → Sign up, gratis,
   solo navegador).
2. **Creá un repositorio nuevo**: botón verde "New" → nombre (ej.
   `handheld-alert`) → puede ser privado → "Create repository".
3. **Subí los archivos del proyecto**:
   - En la página del repo recién creado, click en el link "uploading an
     existing file".
   - Arrastrá el CONTENIDO de esta carpeta `HandheldAlert` (todo lo que está
     adentro: `app/`, `build.gradle.kts`, `settings.gradle.kts`,
     `gradle.properties`, `gradle/`, `README.md`, etc.) — Chrome/Edge
     soportan arrastrar carpetas enteras.
   - **La carpeta `.github` es oculta en Windows** y puede no arrastrarse
     sola. Si no aparece en el drag & drop, agregala a mano: en el repo,
     "Add file" → "Create new file" → como nombre escribí
     `.github/workflows/build-apk.yml` (GitHub crea las carpetas solo) →
     pegá el contenido de ese archivo (lo tenés en el zip) → "Commit
     changes".
   - Hacé commit de todo a la rama `main`.
4. **Se compila solo**: andá a la pestaña "Actions" del repo — el workflow
   "Build debug APK" arranca automáticamente. Tarda unos minutos.
5. **Descargá el APK**: cuando el run termine (tilde verde), entrá a ese
   run → abajo, en "Artifacts", descargá `handheld-alert-debug-apk` (es un
   .zip que contiene el `app-debug.apk`).
6. Si más adelante cambiás código, repetí el paso 3 (subir/editar archivos
   en GitHub) — cada commit dispara un build nuevo automáticamente.

## Alternativa: compilar en Android Studio

Si en algún momento tenés una PC donde sí podés instalar software:

1. Abrí Android Studio (Koala o más nuevo) → **Open** → elegí esta carpeta
   (`HandheldAlert`).
2. Al abrir por primera vez, Android Studio te va a ofrecer generar el
   Gradle Wrapper si falta (`gradle/wrapper/gradle-wrapper.jar` no viene
   incluido en este zip). Aceptá esa sugerencia, o corré una vez:
   ```
   gradle wrapper --gradle-version 8.7
   ```
   si tenés Gradle instalado localmente.
3. Sync del proyecto y compilá (`Build > Make Project` o `Run`).

## Pasar el APK a la handheld

La handheld normalmente permite instalar APKs sueltos (fuera de Play Store)
aunque sea un dispositivo de empresa — depende de su configuración, pero
vale la pena probar. Opciones, de más a menos simple:

- **Descargar directo en la handheld**: si tiene su propio navegador y wifi,
  subí el `app-debug.apk` a algo que puedas abrir desde ahí (Drive, un mail
  a una casilla que revises en la handheld, etc.) y descargalo directo.
- **Cable USB**: conectá la handheld a una PC (no hace falta permisos de
  admin para copiar un archivo por USB) y copiá el `.apk` a su
  almacenamiento; después abrilo desde un explorador de archivos en la
  handheld para instalarlo.
- **Si nada de esto funciona** (la handheld bloquea instalación de APKs
  externos por política de la empresa/MDM), contame y lo charlamos — puede
  que haga falta que el área de sistemas de OCASA habilite la instalación o
  la distribuya ellos.

Al instalar por primera vez, Android va a advertir que es una app de "fuente
desconocida" — es normal para un APK compilado por fuera de Play Store; hay
que confirmar "Instalar de todos modos" (el texto exacto varía según la
versión de Android).

## Instalar y configurar en el handheld

1. Instalá el APK en el handheld (con Dolphin/Chrome ya instalado).
2. Abrí **Handheld Alert** y tocá en orden los botones:
   1. **Habilitar permiso de superposición** — te lleva a Ajustes, activá
      "Permitir sobre otras apps".
   2. **Habilitar servicio de accesibilidad** — en Ajustes > Accesibilidad,
      buscá "Handheld Alert" y activalo.
   3. **Calibrar zona del ícono** — con el navegador mostrando la pantalla de
      SAP donde sale error.gif / warning.gif / enter.gif (siempre pegado al
      margen izquierdo, primera fila debajo del título), volvé a esta app y
      arrastrá un rectángulo sobre esa zona (en proporción a la pantalla
      completa, no hace falta que sea pixel-perfecto, pero cuanto más
      ajustado mejor).
   4. **Iniciar captura de pantalla** — Android va a pedir confirmación de
      "compartir/grabar pantalla"; aceptá. A partir de ahí el servicio queda
      corriendo en primer plano (aparece una notificación fija) monitoreando
      la pantalla.
3. Probá con el botón **"Probar alerta"** en la app para ver cómo se ve el
   overlay verde antes de usarlo en SAP.

## Paquetes de navegador monitoreados

Por defecto `RouteAccessibilityService` sólo lee texto de:
- Dolphin Browser (`mobi.mgeek.TunnyBrowser`)
- Chrome (`com.android.chrome` y variantes)

Si el handheld usa otro navegador o un paquete distinto, agregalo en
`DetectionConfig.DEFAULT_BROWSER_PACKAGES` (archivo
`app/src/main/java/com/gaston/handheldalert/DetectionConfig.kt`).

## Ajustar la detección de color

Si en la práctica el rojo/verde no se detecta bien (por ejemplo si el ícono
es muy chico o el fondo tiene otro color), los umbrales están en
`IconColorAnalyzer.kt` (variable `threshold`, 0.04 por defecto — bajalo si no
detecta, subilo si detecta de más).

## Limitaciones conocidas

- La calibración es "a ciegas": esta versión no muestra una foto en vivo de
  la pantalla real durante la calibración (Android no deja ver otra app
  detrás sin capturarla primero). Conviene calibrar mirando la posición del
  ícono a ojo (esquina, % aproximado del ancho/alto) y ajustar por prueba y
  error con el botón de test.
- Funciona a partir de Android 8.0 (API 26) por el uso de
  `TYPE_APPLICATION_OVERLAY` y `MediaProjection` en foreground service.
- El texto sólo se lee de apps cuyo paquete esté en la lista de navegadores
  monitoreados.


## v1.0.1
- La pantalla principal ahora permite desplazamiento vertical para que los botones de captura y prueba sean accesibles en Honeywell con pantalla pequeña.


## v1.0.4 diagnóstico
- Corrige el orden de parámetros de `MediaProjection#createVirtualDisplay`.
- Muestra el error real de inicio de captura en `MainActivity`.
- Informa el estado de captura mediante broadcast interno.
- Libera correctamente el bitmap temporal cuando hay row padding.
