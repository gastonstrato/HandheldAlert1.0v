package com.gaston.handheldalert

import java.util.regex.Pattern

/**
 * Guarda el último texto leído del navegador por RouteAccessibilityService
 * y lo clasifica (éxito/error) para armar la alerta.
 *
 * Para el estado de éxito (enter.gif / tilde verde), la pantalla de SAP
 * (visto en YV33_RT, y ahora también esperado en ZV29) muestra una línea de
 * progreso con ruta, orden, total, leído y faltan — ej:
 * "R:8 O:7 total:33 Leido:30 Faltan:3". El overlay la parte en dos líneas:
 * grande "R8" y abajo, más chico, "O:7 -total:33 -Leido:30 - Faltan:3".
 */
object ScreenTextHolder {

    // Ej: "R 12345", "R12345", "R:8", "Ruta 12345"
    private val ROUTE_PATTERN: Pattern = Pattern.compile(
        "\\b[Rr](?:uta)?\\.?:?\\s?(\\d+)\\b"
    )
    private val ORDER_PATTERN: Pattern = Pattern.compile("\\b[Oo]\\.?:?\\s?(\\d+)\\b")
    private val TOTAL_PATTERN: Pattern = Pattern.compile("[Tt]otal\\.?:?\\s?(\\d+)")
    private val LEIDO_PATTERN: Pattern = Pattern.compile("[Ll]e[ií]do\\.?:?\\s?(\\d+)")
    private val FALTAN_PATTERN: Pattern = Pattern.compile("[Ff]altan\\.?:?\\s?(\\d+)")

    private val ERROR_PATTERNS = compileAll(DetectionConfig.ERROR_TEXT_PATTERNS)
    private val WARNING_PATTERNS = compileAll(DetectionConfig.WARNING_TEXT_PATTERNS)
    private val SUCCESS_PATTERNS = compileAll(DetectionConfig.SUCCESS_TEXT_PATTERNS)

    @Volatile
    var lastFullScreenText: String = ""
        private set

    @Volatile
    var lastRouteNumber: String? = null
        private set

    @Volatile
    var lastOrderNumber: String? = null
        private set

    @Volatile
    var lastTotal: String? = null
        private set

    @Volatile
    var lastLeido: String? = null
        private set

    @Volatile
    var lastFaltan: String? = null
        private set

    fun update(screenText: String) {
        lastFullScreenText = screenText
        lastRouteNumber = firstMatch(ROUTE_PATTERN, screenText)
        lastOrderNumber = firstMatch(ORDER_PATTERN, screenText)
        lastTotal = firstMatch(TOTAL_PATTERN, screenText)
        lastLeido = firstMatch(LEIDO_PATTERN, screenText)
        lastFaltan = firstMatch(FALTAN_PATTERN, screenText)
    }

    /**
     * Clasificación por texto (sin captura de pantalla ni gifs), estricta:
     * solo dispara alerta si el texto matchea alguno de los patrones
     * configurados en DetectionConfig, o si aparece el bloque completo de
     * éxito (ruta + orden + total + leído + faltan, todos juntos — el
     * formato real de la pantalla de SAP). Cualquier otro texto (campos
     * normales de la transacción, el teclado en pantalla, navegación, etc.)
     * no dispara nada. Prioridad si matchea más de un grupo: error > warning
     * > éxito.
     *
     * Importante: NO alcanza con encontrar una "R" seguida de un dígito en
     * cualquier lado del texto (eso disparaba falsos verdes con el teclado
     * en pantalla, ej. al escribir la letra "R" en un campo). Se exige el
     * detalle completo junto, porque así sale siempre en la pantalla real.
     */
    fun classify(): AlertState {
        if (lastFullScreenText.isBlank()) return AlertState.NONE
        val text = lastFullScreenText
        return when {
            matchesAny(ERROR_PATTERNS, text) -> AlertState.ERROR
            matchesAny(WARNING_PATTERNS, text) -> AlertState.WARNING
            hasFullRouteDetail() || matchesAny(SUCCESS_PATTERNS, text) -> AlertState.SUCCESS
            else -> AlertState.NONE
        }
    }

    private fun hasFullRouteDetail(): Boolean =
        lastRouteNumber != null && lastOrderNumber != null &&
            lastTotal != null && lastLeido != null && lastFaltan != null

    private fun firstMatch(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun matchesAny(patterns: List<Pattern>, text: String): Boolean =
        patterns.any { it.matcher(text).find() }

    private fun compileAll(patterns: List<String>): List<Pattern> =
        patterns.map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }

    /**
     * Mensaje de dos líneas para el overlay de éxito:
     * línea 1 (grande) = "R<número>"
     * línea 2 (chica)  = "O:<orden> -total:<total> -Leido:<leido> - Faltan:<faltan>"
     * separadas por "\n" — OverlayAlertManager achica la segunda línea.
     * Si no se encontró el detalle completo (O/total/Leido/Faltan), muestra
     * solo la ruta o, en su defecto, el texto crudo leído de la pantalla.
     */
    fun successMessage(): String {
        val route = lastRouteNumber
        val order = lastOrderNumber
        val total = lastTotal
        val leido = lastLeido
        val faltan = lastFaltan

        if (route == null) return lastFullScreenText.take(80)

        val big = "R$route"
        if (order == null || total == null || leido == null || faltan == null) {
            return big
        }
        val small = "O:$order -total:$total -Leido:$leido - Faltan:$faltan"
        return "$big\n$small"
    }
}
