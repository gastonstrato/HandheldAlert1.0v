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
}
