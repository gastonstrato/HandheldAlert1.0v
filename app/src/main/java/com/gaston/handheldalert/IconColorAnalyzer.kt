package com.gaston.handheldalert

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Analiza el recorte de pantalla correspondiente a la zona calibrada del
 * ícono y decide si lo que se ve ahí es error.gif (rojo), warning.gif
 * (oliva/mostaza) o enter.gif (verde), en base al color dominante de los
 * píxeles no-blancos.
 *
 * Los umbrales de color están calibrados con los colores reales medidos
 * directamente de los gifs de SAP ITSmobile
 * (/sap/public/bc/its/mimes/itsmobile/99/images/all/) el 11/09/2026:
 * - error.gif   → RGB(111, 24, 27)  — rojo, R muy por encima de G y B.
 * - warning.gif → RGB(153, 140, 49) — oliva, R y G altos y parecidos, B bajo.
 * - enter.gif   → RGB(26, 71, 23)   — verde, G por encima de R y B.
 *
 * Es intencionalmente simple (no reconocimiento de forma): alcanza porque
 * estos gifs son de un color sólido bien diferenciado sobre fondo
 * blanco/gris.
 */
object IconColorAnalyzer {

    fun classify(bitmap: Bitmap): AlertState {
        var redScore = 0L
        var warningScore = 0L
        var greenScore = 0L
        var sampled = 0

        val stepX = maxOf(1, bitmap.width / 40)
        val stepY = maxOf(1, bitmap.height / 40)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Ignorar píxeles casi blancos/grises (fondo) y casi negros
                // (texto/bordes, que no aportan al color del ícono).
                val isBackground = (r > 200 && g > 200 && b > 200) ||
                    (r < 20 && g < 20 && b < 20) ||
                    (kotlin.math.abs(r - g) < 12 && kotlin.math.abs(g - b) < 12)

                if (!isBackground) {
                    when {
                        // Oliva/mostaza: R y G ambos sustancialmente por
                        // encima de B, y parecidos entre sí.
                        r > 70 && g > 60 && b < r * 0.65 && b < g * 0.65 &&
                            kotlin.math.abs(r - g) < 45 -> warningScore++
                        // Rojo: R domina claramente sobre G y B.
                        r > g * 1.4 && r > b * 1.3 -> redScore++
                        // Verde: G domina claramente sobre R y B.
                        g > r * 1.3 && g > b * 1.3 -> greenScore++
                    }
                }
                sampled++
                x += stepX
            }
            y += stepY
        }

        if (sampled == 0) return AlertState.NONE

        val redRatio = redScore.toDouble() / sampled
        val warningRatio = warningScore.toDouble() / sampled
        val greenRatio = greenScore.toDouble() / sampled

        // Umbral mínimo para no disparar con ruido de la pantalla.
        val threshold = 0.04
        val best = maxOf(redRatio, warningRatio, greenRatio)
        if (best <= threshold) return AlertState.NONE

        return when (best) {
            redRatio -> AlertState.ERROR
            warningRatio -> AlertState.WARNING
            else -> AlertState.SUCCESS
        }
    }
}
