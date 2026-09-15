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

    // Marcadores de la pantalla "Lectura Ruteador": esa tabla trae Nro.
    // Seguimiento, Fecha Jornada, ID Jornada, Razón Social, Dirección, Ruta,
    // Orden — sin Total/Leído/Faltan (es una transacción distinta a
    // Apertura de HU), así que el bloque R/O/total/Leído/Faltan nunca va a
    // aparecer ahí. Estos dos textos son específicos de esa tabla, no
    // aparecen en otras pantallas (ej. Apertura de HU tiene "Doc. Retiro",
    // "Bultos", "Tranfer", no "Nro. Seguimiento" ni "Razón Social").
    private val NRO_SEGUIMIENTO_MARKER = Pattern.compile("Nro\\.?\\s*Seguimiento", Pattern.CASE_INSENSITIVE)
    private val RAZON_SOCIAL_MARKER = Pattern.compile("Raz[oó]n\\s*Social", Pattern.CASE_INSENSITIVE)

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
            hasFullRouteDetail() || matchesAny(SUCCESS_PATTERNS, text) ||
                lecturaRuteadorRowSignature() != null -> AlertState.SUCCESS
            else -> AlertState.NONE
        }
    }

    private fun hasFullRouteDetail(): Boolean =
        lastRouteNumber != null && lastOrderNumber != null &&
            lastTotal != null && lastLeido != null && lastFaltan != null

    /**
     * "Firma" del resultado actual (ruta+orden+total+leído+faltan juntos),
     * o null si no está el detalle completo. Cambia exactamente cuando se
     * lee un código nuevo (el bloque entero se reemplaza), así que sirve
     * para que RouteAccessibilityService sepa cuándo de verdad hay un
     * escaneo nuevo — a diferencia de cada lectura de accesibilidad, que
     * puede repetirse muchas veces sin que haya pasado nada.
     */
    fun resultSignature(): String? {
        if (!hasFullRouteDetail()) return null
        return "$lastRouteNumber|$lastOrderNumber|$lastTotal|$lastLeido|$lastFaltan"
    }

    /**
     * Firma de fila para "Lectura Ruteador": esa pantalla no tiene el bloque
     * R/O/total/Leído/Faltan, así que la firma de "hay un paquete nuevo" es
     * directamente el bloque de datos completo de la fila (todo lo que
     * aparece después del encabezado de la tabla) — la combinación de Nro.
     * Seguimiento + Fecha Jornada + ID Jornada + Razón Social + Dirección +
     * Ruta + Orden nunca se repite igual entre dos escaneos.
     */
    fun lecturaRuteadorRowSignature(): String? {
        val text = lastFullScreenText
        if (!NRO_SEGUIMIENTO_MARKER.matcher(text).find() || !RAZON_SOCIAL_MARKER.matcher(text).find()) {
            return null
        }
        val headerEnd = text.lastIndexOf("Orden", ignoreCase = true)
        if (headerEnd == -1) return null
        var dataRegion = text.substring(headerEnd + "Orden".length)
        val footerIndex = dataRegion.indexOf("pág.", ignoreCase = true)
        if (footerIndex >= 0) dataRegion = dataRegion.substring(0, footerIndex)
        return dataRegion.trim().ifBlank { null }
    }

    /** Firma unificada: la que esté disponible según la pantalla actual. */
    fun currentSignature(): String? = resultSignature() ?: lecturaRuteadorRowSignature()

    private fun firstMatch(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun matchesAny(patterns: List<Pattern>, text: String): Boolean =
        patterns.any { it.matcher(text).find() }

    private fun compileAll(patterns: List<String>): List<Pattern> =
        patterns.map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }

    /**
     * Mensaje para el overlay de éxito. Si está el bloque completo de
     * Apertura de HU (ruta+orden+total+leído+faltan), arma dos líneas:
     * grande "R<número>" y abajo, más chico,
     * "O:<orden> -total:<total> -Leido:<leido> - Faltan:<faltan>"
     * (OverlayAlertManager achica la segunda línea). Si en cambio lo que
     * hay es la fila de Lectura Ruteador, muestra esa fila. En su defecto,
     * el texto crudo leído de la pantalla.
     */
    fun successMessage(): String {
        if (hasFullRouteDetail()) {
            val big = "R$lastRouteNumber"
            val small = "O:$lastOrderNumber -total:$lastTotal -Leido:$lastLeido - Faltan:$lastFaltan"
            return "$big\n$small"
        }

        // Ojo: se chequea ANTES que "solo ruta", porque en Lectura Ruteador
        // el encabezado "Ruta" de la tabla puede matchear ROUTE_PATTERN por
        // casualidad (ej. si hay un dígito suelto cerca) sin que en verdad
        // estemos ante el bloque de Apertura de HU.
        lecturaRuteadorRowSignature()?.let { return it.take(80) }

        if (lastRouteNumber != null) return "R$lastRouteNumber"

        return lastFullScreenText.take(80)
    }
}
