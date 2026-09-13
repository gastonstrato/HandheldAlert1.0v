package com.gaston.handheldalert

import java.util.regex.Pattern

/**
 * Puente simple entre RouteAccessibilityService (que lee los nodos de texto
 * del navegador) y ScreenWatchService (que analiza el color del ícono).
 * Cuando ScreenWatchService detecta error/warning/enter, consulta acá cuál
 * es el último texto/mensaje visible para armar la alerta.
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
     * Clasificación por texto (sin captura de pantalla ni gifs): si el texto
     * leído trae el patrón de ruta ("R:8", "R 12345", etc.) es éxito (verde);
     * si hay texto pero no matchea ruta, se trata como error (rojo) por
     * ahora. El warning (amarillo) se suma más adelante, cuando se defina
     * qué lo distingue de un error en el texto.
     */
    fun classify(): AlertState {
        if (lastFullScreenText.isBlank()) return AlertState.NONE
        return if (lastRouteNumber != null) AlertState.SUCCESS else AlertState.ERROR
    }

    private fun firstMatch(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

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
