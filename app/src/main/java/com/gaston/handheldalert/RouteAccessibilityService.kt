package com.gaston.handheldalert

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lee el árbol de accesibilidad de la ventana del navegador (Dolphin o Chrome)
 * y junta el texto visible en una sola cadena. De ahí ScreenTextHolder saca
 * el número de ruta ("R 12345") para usarlo en la alerta grande.
 *
 * La detección de qué alerta mostrar es por texto, no por color de gif: si
 * aparece el patrón de ruta ("R:8" y similares) es éxito (verde), cualquier
 * otro texto se trata como error (rojo) por ahora — el aviso (amarillo) se
 * suma más adelante.
 *
 * No alcanza con reaccionar solo a onAccessibilityEvent: un WebView viejo
 * como Dolphin no siempre avisa a tiempo (o directamente no avisa) cuando la
 * página cambia por JS, así que además se sondea el árbol activo cada
 * [POLL_INTERVAL_MS] mientras el navegador esté al frente. Esto acota la
 * demora a ese intervalo en vez de depender de que el evento llegue.
 */
class RouteAccessibilityService : AccessibilityService() {

    companion object {
        private const val POLL_INTERVAL_MS = 200L

        // Cuántas lecturas seguidas de "nada" hacen falta antes de ocultar
        // la alerta. Mostrar sigue siendo instantáneo (1 sola lectura); esto
        // solo evita que un bache de una lectura a mitad de un re-render de
        // la página (falta un dato por una fracción de segundo) apague y
        // vuelva a prender el overlay — el parpadeo reportado en la Zebra.
        private const val HIDE_AFTER_CONSECUTIVE_NONE = 3
    }

    private val overlayManager by lazy { OverlayAlertManager(applicationContext) }
    private val pollHandler = Handler(Looper.getMainLooper())
    private var noneStreak = 0

    private val pollLoop = object : Runnable {
        override fun run() {
            readAndClassify()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        pollHandler.post(pollLoop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isMonitoredPackage(pkg)) return
        readAndClassify()
    }

    private fun readAndClassify() {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val pkg = root.packageName?.toString()
        if (pkg == null || !isMonitoredPackage(pkg)) {
            root.recycle()
            return
        }

        val builder = StringBuilder()
        collectText(root, builder)
        root.recycle()

        if (builder.isEmpty()) return
        ScreenTextHolder.update(builder.toString())

        when (val detected = ScreenTextHolder.classify()) {
            AlertState.NONE -> {
                noneStreak++
                if (noneStreak >= HIDE_AFTER_CONSECUTIVE_NONE) {
                    overlayManager.hide()
                }
            }
            AlertState.SUCCESS -> {
                noneStreak = 0
                overlayManager.show(AlertState.SUCCESS, ScreenTextHolder.successMessage())
            }
            AlertState.ERROR, AlertState.WARNING -> {
                noneStreak = 0
                overlayManager.show(detected, ScreenTextHolder.lastFullScreenText.take(80))
            }
        }
    }

    private fun isMonitoredPackage(pkg: String): Boolean {
        // Por defecto Dolphin/Chrome; si el usuario usa otro navegador,
        // puede sumar su paquete en DetectionConfig.DEFAULT_BROWSER_PACKAGES.
        return DetectionConfig.DEFAULT_BROWSER_PACKAGES.contains(pkg)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int = 0) {
        if (node == null || depth > 40) return
        // Ojo: no seguir con nodos no visibles (ej. opciones ocultas de un
        // <select> colapsado) — si no, su texto se suma igual aunque no
        // aparezca en pantalla, y puede matchear un patrón por error.
        if (!node.isVisibleToUser) return
        val text = node.text
        if (!text.isNullOrBlank()) {
            out.append(text).append(' ')
        }
        val desc = node.contentDescription
        if (!desc.isNullOrBlank()) {
            out.append(desc).append(' ')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out, depth + 1)
            child.recycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(pollLoop)
    }

    override fun onInterrupt() {
        // No-op
    }
}
