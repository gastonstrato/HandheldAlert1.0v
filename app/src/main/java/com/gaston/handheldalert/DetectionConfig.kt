package com.gaston.handheldalert

/**
 * Paquetes de navegador cuyo texto lee RouteAccessibilityService.
 */
object DetectionConfig {

    // Paquetes de navegador soportados por defecto (Dolphin y Chrome).
    // El usuario puede agregar el suyo si usa otro navegador en el handheld.
    val DEFAULT_BROWSER_PACKAGES = setOf(
        "mobi.mgeek.TunnyBrowser", // Dolphin Browser
        "com.android.chrome",
        "com.chrome.beta",
        "com.google.android.apps.chrome"
    )

    // Patrones (regex, case-insensitive) que ScreenTextHolder.classify() busca
    // dentro del texto leído del navegador para decidir el color de la
    // alerta. Si el texto no matchea ninguno de los tres grupos, no se
    // muestra alerta. Orden de prioridad si matchea más de uno: error >
    // warning > éxito.
    val ERROR_TEXT_PATTERNS = listOf(
        "es de ingreso obligatorio",
        "no existe",
        "no autorizado",
        "error",
        "No ha seleccionado equipo p/",
        "No pertenece a esta HU"
    )
    val WARNING_TEXT_PATTERNS = listOf(
        "verifique",
        "revise",
        "atención"
    )
    val SUCCESS_TEXT_PATTERNS = listOf(
        "confirmado",
        "grabado",
        "R:\\d+ O:\\d+"
    )
}
