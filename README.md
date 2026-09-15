# Handheld Alert

App Android que se superpone al navegador (Dolphin o Chrome) del handheld y
muestra una alerta grande en el **75% superior** de la pantalla (el 25% de
abajo queda libre para seguir completando los campos de la transacción,
ej. Tipo/Número/Nro. Doc/Id. Clie/Cierre/Ordenar no leídos en ZV29):

- **Verde** (`#215A1C`) cuando el texto leído de la pantalla trae el patrón
  de ruta (ej. `R:8`, `R 12345`), mostrando ese número.
- **Roja** (`#8C1F23`) para cualquier otro texto, por ahora — se irá
  ajustando con patrones/palabras clave concretas de aviso y error.

## Cómo funciona (resumen técnico)

La detección es puramente por texto, vía accesibilidad — no usa captura de
pantalla ni reconocimiento de color de íconos:

`RouteAccessibilityService` lee el árbol de accesibilidad del navegador cada
vez que cambia el contenido y junta todo el texto visible. `ScreenTextHolder`
clasifica ese texto: si matchea el patrón de ruta (`R <número>`) es éxito
(verde); cualquier otro texto se trata como error (rojo) por ahora. Se pide
2 lecturas seguidas iguales antes de actuar, para no parpadear con eventos
intermedios (carga de página, autocompletado, etc.).

`OverlayAlertManager` muestra el overlay ocupando el 75% superior de la
pantalla (`Gravity.TOP`, alto = `OVERLAY_HEIGHT_FRACTION *
displayMetrics.heightPixels`), rojo o verde según el estado, con el mensaje.
El 25% inferior de la pantalla real queda sin cubrir — los toques ahí van
directo al navegador.

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
3. Probá con el botón **"Probar alerta"** en la app para ver cómo se ve el
   overlay verde antes de usarlo en SAP. Con los dos permisos habilitados,
   la detección ya queda corriendo sola en background — no hace falta
   ningún paso de captura ni calibración.

## Paquetes de navegador monitoreados

Por defecto `RouteAccessibilityService` sólo lee texto de:
- Dolphin Browser (`mobi.mgeek.TunnyBrowser`)
- Chrome (`com.android.chrome` y variantes)

Si el handheld usa otro navegador o un paquete distinto, agregalo en
`DetectionConfig.DEFAULT_BROWSER_PACKAGES` (archivo
`app/src/main/java/com/gaston/handheldalert/DetectionConfig.kt`).

## Ajustar la detección de texto

Hoy `ScreenTextHolder.classify()` es deliberadamente simple: verde solo si
aparece el patrón de ruta, rojo para cualquier otro texto. Esto es un punto
de partida — falta sumar patrones/palabras clave específicas para separar
aviso (amarillo, todavía sin implementar) de error real, y evitar que
cualquier texto normal de la pantalla (mientras se completan campos)
dispare rojo de más.

## Limitaciones conocidas

- Al leer todo el árbol de accesibilidad de la ventana activa, cualquier
  cambio de texto en el navegador monitoreado (no solo los mensajes de SAP)
  puede disparar una reclasificación. Se está ajustando con patrones más
  específicos.
- Funciona a partir de Android 8.0 (API 26) por el uso de
  `TYPE_APPLICATION_OVERLAY`.
- El texto sólo se lee de apps cuyo paquete esté en la lista de navegadores
  monitoreados.


## v1.3.0
- Se suma soporte para la pantalla "Lectura Ruteador" (transacción
  distinta a Apertura de HU, sin bloque Total/Leído/Faltan): la firma de
  "hay un paquete nuevo" ahí es la fila completa de la tabla (Nro.
  Seguimiento + Fecha Jornada + ID Jornada + Razón Social + Dirección +
  Ruta + Orden), que nunca se repite igual entre dos escaneos. Antes esta
  pantalla no disparaba ninguna alerta.
- `ScreenTextHolder.currentSignature()` unifica ambas firmas (la de
  Apertura de HU y la de Lectura Ruteador) para que
  RouteAccessibilityService no tenga que saber en qué pantalla está.
- El destello (dim y vuelta) se reemplaza por un fade-in real (alpha 0→1)
  cada vez que cambia el dato mostrado, más acorde a "reiniciar" la
  ventana con la nueva pieza escaneada.

## v1.2.1
- Corrige que se quedara mostrando el paquete anterior al escanear uno
  nuevo: el mensaje se armaba recién dentro del callback de 200ms, y en
  ese lapso el sondeo podía volver a pisar el estado global de
  `ScreenTextHolder` con una lectura vieja/parcial del WebView. Ahora el
  mensaje se captura en el momento exacto en que se detecta el cambio de
  firma, antes de programar el parpadeo.

## v1.2.0
- Rediseño para frenar el parpadeo de raíz: en vez de reaccionar en cada
  lectura de accesibilidad, el overlay ahora solo cambia en dos casos:
  aparece un error/aviso conocido (se oculta y no muestra nada, por ahora)
  o el bloque de resultado (ruta+orden+total+leído+faltan) cambia a
  valores distintos a los últimos mostrados (eso es "código nuevo": oculta,
  espera 200ms, muestra el dato nuevo). Cualquier otra lectura no toca el
  overlay para nada — ni la misma alerta repetida, ni texto sin patrones.
- El rojo de error queda deshabilitado temporalmente (solo cierra la
  ventana) mientras se prueba que el verde funcione sin parpadeo.

## v1.1.8
- El fix anterior del parpadeo contaba "lecturas seguidas de nada", pero con
  varios tipos de evento de accesibilidad activos y notificationTimeout=0,
  `readAndClassify()` se puede llamar muchas veces por segundo — así que 3
  lecturas podían pasar en 50ms, no en 600ms, y el parpadeo seguía. Ahora se
  mide por tiempo real transcurrido (800ms sin matchear nada, sin importar
  cuántas lecturas hubo en el medio) antes de ocultar la alerta.

## v1.1.7
- Corrige el parpadeo del overlay verde en la Zebra: al sacar todo
  debounce, un solo sondeo que agarrara la página a mitad de un re-render
  (falta un dato del bloque de éxito por una fracción de segundo) apagaba
  la alerta y la siguiente lectura la volvía a prender. Ahora mostrar
  sigue siendo instantáneo, pero ocultar pide 3 lecturas seguidas de
  "nada" (~600ms) antes de actuar.

## v1.1.6
- Se suman patrones de error nuevos: "No se encontraron datos de
  Seguimientos" y "La Unidad de Manipulación "..." se encuentra
  Desconsolidada" (el código entre comillas es variable, se matchea
  cualquier contenido ahí).
- Se suma un formato alternativo de éxito con comillas:
  `R:"..",O:".." -total:".." -Leido:".." - Faltan:".."`.
- Texto del overlay más grande: 64sp (antes 45sp) y la segunda línea del
  éxito pasa a 0.27 del tamaño grande (antes 0.42, para que no quede
  desproporcionada con el tamaño nuevo).

## v1.1.5
- Se endurece la detección de éxito: antes alcanzaba con encontrar una "R"
  seguida de un dígito en cualquier parte del texto leído, lo que disparaba
  verde falso al escribir la letra "R" con el teclado en pantalla (si
  cerca, en cualquier otro nodo, había algún dígito suelto). Ahora exige el
  bloque completo junto — ruta + orden + total + leído + faltan — que es el
  formato real y siempre igual de la pantalla de éxito de SAP.
- Error/aviso ya funcionaban así (solo las frases cargadas en
  `DetectionConfig`, nada por defecto): sin cambios ahí.

## v1.1.4
- La demora e inconsistencia en la Zebra TC25AJ venía de depender solo de
  que el WebView (Dolphin) avise el cambio de pantalla por evento de
  accesibilidad, algo poco confiable en apps web viejas. Se agrega un
  sondeo activo del árbol de accesibilidad cada 200ms mientras el
  navegador está al frente, además de seguir escuchando eventos — así la
  demora queda acotada aunque el evento no llegue o llegue tarde.
- Se baja `notificationTimeout` del servicio de accesibilidad de 150ms a
  0 (menos coalescing de eventos por parte del sistema) y se suman más
  tipos de evento (`typeViewTextChanged`, `typeWindowsChanged`).

## v1.1.3
- `RouteAccessibilityService` ahora ignora nodos no visibles (ej. opciones
  ocultas de un `<select>` colapsado). Antes se sumaban al texto igual,
  aunque no aparecieran en pantalla, y podían matchear un patrón de error
  por error (visto en la pantalla "Lectura Ruteador", que no tiene ninguna
  palabra clave visible).
- Se agrega un botón "Ver último texto leído (debug)" en la pantalla
  principal para poder confirmar en el momento qué texto está leyendo la
  app y en qué color lo clasificó, sin necesitar logs ni cable.

## v1.1.2
- Se saca el debounce de 2 lecturas iguales antes de mostrar la alerta: ya no
  hace falta, la clasificación es por patrones específicos, y esperar una
  segunda lectura igual generaba demora (a veces la alerta no llegaba a
  aparecer si SAP no repetía el mismo texto).
- El overlay ahora se refresca y destella cada vez que cambia el contenido
  real (ej. entra un paquete nuevo de la misma ruta y sigue en verde), para
  que se note que hubo una lectura nueva aunque el color no cambie.

## v1.1.1
- Agrega ícono propio de launcher (`ic_launcher`/`ic_launcher_round`, adaptive
  icon): antes usaba `@android:drawable/ic_menu_view`, un ícono de sistema
  para menús que varios launchers (sobre todo en dispositivos corporativos)
  no mostraban en la pantalla principal.

## v1.1.0
- Se reemplaza la detección por color de gif (captura de pantalla +
  `MediaProjection`, calibración manual de zona) por detección puramente de
  texto vía accesibilidad: verde si el texto trae el patrón de ruta, rojo
  para el resto por ahora.
- Se eliminan `ScreenWatchService`, `IconColorAnalyzer`, `CalibrationActivity`
  y `SelectionOverlayView` (ya sin uso), junto con los pasos de calibrar
  zona e iniciar captura de la pantalla principal.

## v1.0.1
- La pantalla principal ahora permite desplazamiento vertical para que los botones de captura y prueba sean accesibles en Honeywell con pantalla pequeña.


## v1.0.4 diagnóstico
- Corrige el orden de parámetros de `MediaProjection#createVirtualDisplay`.
- Muestra el error real de inicio de captura en `MainActivity`.
- Informa el estado de captura mediante broadcast interno.
- Libera correctamente el bitmap temporal cuando hay row padding.

## v1.0.5
- Corrige el orden de parámetros de `MediaProjection.createVirtualDisplay` para Android/Kotlin.
