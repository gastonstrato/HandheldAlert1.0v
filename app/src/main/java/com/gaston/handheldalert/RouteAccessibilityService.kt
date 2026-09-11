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
 * No lee imágenes (los <img> del WebView normalmente no exponen su src acá,
 * solo alt/contentDescription si el HTML lo trae) — el color/ícono lo detecta
 * ScreenWatchService por captura de pantalla.
 */
class RouteAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isMonitoredPackage(pkg)) return

        val root = rootInActiveWindow ?: return
        val builder = StringBuilder()
        collectText(root, builder)
        root.recycle()

        if (builder.isNotEmpty()) {
            ScreenTextHolder.update(builder.toString())
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
