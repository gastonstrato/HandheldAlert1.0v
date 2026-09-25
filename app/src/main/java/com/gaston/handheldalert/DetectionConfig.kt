package com.gaston.handheldalert

import android.content.Context
import android.view.KeyEvent

/**
 * Paquetes de navegador cuyo texto lee RouteAccessibilityService, patrones
 * de texto por estado, y la tecla física de escaneo que cierra la alerta.
 */
object DetectionConfig {

    private const val PREFS = "handheld_alert_prefs"
    private const val KEY_SCAN_KEYCODE = "scan_keycode"

    // Códigos que suelen mandar los gatillos de escaneo si no se configuró
    // uno a mano. Varía según modelo y según cómo esté configurado el
    // equipo (Key Programmer / DataWedge), por eso existe el modo aprender.
    private val DEFAULT_SCAN_KEYCODES = setOf(
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        10036 // Zebra: código "SCAN" en equipos con teclas remapeadas
    )

    // true mientras MainActivity espera que el usuario apriete el gatillo
    // para registrarlo (mismo proceso que el servicio de accesibilidad).
    @Volatile
    var learningScanKey = false

    fun learnedScanKeyCode(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_SCAN_KEYCODE)) prefs.getInt(KEY_SCAN_KEYCODE, 0) else null
    }

    fun saveScanKeyCode(context: Context, keyCode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SCAN_KEYCODE, keyCode)
            .apply()
    }

    fun isScanKey(context: Context, keyCode: Int): Boolean {
        val learned = learnedScanKeyCode(context)
        return if (learned != null) keyCode == learned else keyCode in DEFAULT_SCAN_KEYCODES
    }

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
    // Nota: "" en los patrones que pasó el usuario marca dónde va un valor
    // variable (número/código) que lee SAP en runtime — se traduce acá a
    // "[^"]*" (cualquier contenido entre comillas, comillas reales incluidas
    // en el mensaje de SAP) en vez de comillas vacías literales.
    val ERROR_TEXT_PATTERNS = listOf(
        "es de ingreso obligatorio",
        "no existe",
        "no autorizado",
        "error",
        "No ha seleccionado equipo p/",
        "No pertenece a esta HU",
        "No se encontraron datos de Seguimientos",
        "La Unidad de Manipulación \"[^\"]*\" se encuentra Desconsolidada"
    )
    val WARNING_TEXT_PATTERNS = listOf(
        "verifique",
        "revise",
        "atención"
    )
    val SUCCESS_TEXT_PATTERNS = listOf(
        "confirmado",
        "grabado",
        "R:\\d+ O:\\d+",
        "R:\"[^\"]*\",O:\"[^\"]*\" -total:\"[^\"]*\" -Leido:\"[^\"]*\" - Faltan:\"[^\"]*\""
    )
}
