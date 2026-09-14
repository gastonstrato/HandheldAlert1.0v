package com.gaston.handheldalert

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lee el árbol de accesibilidad de la ventana del navegador (Dolphin o Chrome)
 * cada vez que cambia el contenido, y junta el texto visible en una sola
 * cadena. De ahí ScreenTextHolder saca el número de ruta ("R 12345") para
 * usarlo en la alerta grande.
 *
 * La detección de qué alerta mostrar es por texto, no por color de gif: si
 * aparece el patrón de ruta ("R:8" y similares) es éxito (verde), cualquier
 * otro texto se trata como error (rojo) por ahora — el aviso (amarillo) se
 * suma más adelante. Al ser patrones específicos (no "cualquier texto"), no
 * hace falta debounce: se actúa apenas se lee el texto, sin esperar una
 * segunda lectura igual. OverlayAlertManager es quien decide si hay que
 * refrescar/destellar el overlay (por ejemplo cuando entra un paquete nuevo
 * de la misma ruta y el color no cambia, pero el contenido sí).
 */
class RouteAccessibilityService : AccessibilityService() {

    private val overlayManager by lazy { OverlayAlertManager(applicationContext) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isMonitoredPackage(pkg)) return

        val root = rootInActiveWindow ?: return
        val builder = StringBuilder()
        collectText(root, builder)
        root.recycle()

        if (builder.isEmpty()) return
        ScreenTextHolder.update(builder.toString())

        when (val detected = ScreenTextHolder.classify()) {
            AlertState.NONE -> overlayManager.hide()
            AlertState.SUCCESS -> overlayManager.show(
                AlertState.SUCCESS,
                ScreenTextHolder.successMessage()
            )
            AlertState.ERROR, AlertState.WARNING -> overlayManager.show(
                detected,
                ScreenTextHolder.lastFullScreenText.take(80)
            )
        }
    }

    private fun isMonitoredPackage(pkg: String): Boolean {
        // Por defecto Dolphin/Chrome; si el usuario usa otro navegador,
        // puede sumar su paquete en DetectionConfig.DEFAULT_BROWSER_PACKAGES.
        return DetectionConfig.DEFAULT_BROWSER_PACKAGES.contains(pkg)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int = 0) {
        if (node == null || depth > 40) return
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

    override fun onInterrupt() {
        // No-op
    }
}
