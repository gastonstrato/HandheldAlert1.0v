package com.gaston.handheldalert

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

enum class AlertState { NONE, ERROR, WARNING, SUCCESS }

/**
 * Muestra/oculta la alerta usando un overlay del sistema
 * (TYPE_APPLICATION_OVERLAY), así se ve por encima del navegador (Dolphin o
 * Chrome) sin importar qué app esté al frente.
 *
 * Ocupa solo el 75% superior de la pantalla (Gravity.TOP): el 25% inferior
 * queda libre y clickeable para que el operador siga completando los campos
 * de la transacción (Tipo, Número, Nro. Doc, Id. Clie, Cierre, Ordenar no
 * leídos, etc.) sin que la alerta se los tape.
 */
class OverlayAlertManager(private val context: Context) {

    companion object {
        // Fracción de la altura de pantalla que cubre la alerta. El 25%
        // restante (abajo) queda libre para los campos de la transacción.
        const val OVERLAY_HEIGHT_FRACTION = 0.75f
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    var currentState: AlertState = AlertState.NONE
        private set

    fun show(state: AlertState, message: String) {
        if (state == AlertState.NONE) {
            hide()
            return
        }

        val color = when (state) {
            AlertState.ERROR -> ContextColor.RED
            AlertState.WARNING -> ContextColor.WARNING
            else -> ContextColor.GREEN
        }

        if (overlayView == null) {
            overlayView = buildView()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val displayMetrics = context.resources.displayMetrics
            val overlayHeight = (displayMetrics.heightPixels * OVERLAY_HEIGHT_FRACTION).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayHeight,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP
            params.x = 0
            params.y = 0
            windowManager.addView(overlayView, params)
        }

        val root = overlayView as LinearLayout
        root.setBackgroundColor(color)
        val textView = root.getChildAt(0) as TextView
        textView.text = buildDisplayText(message)

        // Tocar la alerta la oculta manualmente (por si el estado tarda en limpiarse).
        root.setOnClickListener { hide() }

        currentState = state
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
        }
        overlayView = null
        currentState = AlertState.NONE
    }

    /**
     * Si el mensaje trae más de una línea (ej. el detalle de ruta armado por
     * `ScreenTextHolder.successMessage()`: "R8\nO:7 -total:33 -Leido:30 -
     * Faltan:3"), la primera línea queda grande (tamaño normal del
     * TextView) y el resto se muestra más chico y centrado debajo, tal como
     * pidió el usuario.
     */
    private fun buildDisplayText(message: String): CharSequence {
        val newlineIndex = message.indexOf('\n')
        if (newlineIndex == -1) return message

        val spannable = SpannableString(message)
        spannable.setSpan(
            RelativeSizeSpan(0.42f),
            newlineIndex + 1,
            message.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    private fun buildView(): View {
        val text = TextView(context).apply {
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                text,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private object ContextColor {
        // Colores medidos directamente de los gifs reales de SAP ITSmobile
        // (/sap/public/bc/its/mimes/itsmobile/99/images/all/) el 11/09/2026:
        // error.gif ≈ #6F181B, warning.gif ≈ #998C31, enter.gif ≈ #1A4717.
        // Se usan levemente más saturados que el original para que se lean
        // bien como fondo de pantalla completa (el gif es minúsculo y sólido,
        // pero a full-screen un tono demasiado apagado se ve "sucio").
        val RED = Color.parseColor("#8C1F23")
        val WARNING = Color.parseColor("#B39B2E")
        val GREEN = Color.parseColor("#215A1C")
    }
}
