package com.gaston.handheldalert

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lee el árbol de accesibilidad de la ventana del navegador (Dolphin o Chrome)
 * y junta el texto visible en una sola cadena. De ahí ScreenTextHolder saca
 * el número de ruta y el resto del bloque de éxito.
 *
 * Diseño simplificado para no parpadear sin motivo: el overlay solo cambia
 * de estado en dos casos concretos, no en cada lectura de accesibilidad:
 *
 * 1. Aparece un error/aviso conocido -> se oculta la ventana y no se
 *    muestra nada (por ahora; el rojo se vuelve a sumar más adelante,
 *    cuando esto esté probado).
 * 2. El bloque de resultado (ruta+orden+total+leído+faltan) cambia a un
 *    conjunto de valores distinto al último mostrado -> eso significa que
 *    se escaneó un código nuevo: se oculta la ventana, se espera
 *    [BLINK_DELAY_MS] y se vuelve a mostrar con los datos nuevos.
 *
 * Cualquier otra lectura (texto sin patrones, o exactamente el mismo
 * resultado de siempre) no toca el overlay para nada.
 */
class RouteAccessibilityService : AccessibilityService() {

    companion object {
        private const val POLL_INTERVAL_MS = 200L
        private const val BLINK_DELAY_MS = 200L
    }

    private val overlayManager by lazy { OverlayAlertManager(applicationContext) }
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastShownSignature: String? = null

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

        when (ScreenTextHolder.classify()) {
            AlertState.ERROR, AlertState.WARNING -> {
                // Por ahora: ante un error/aviso, cerrar y no mostrar nada.
                lastShownSignature = null
                overlayManager.hide()
            }
            AlertState.SUCCESS -> {
                val signature = ScreenTextHolder.resultSignature()
                if (signature == null) {
                    // Matcheó por una palabra suelta (ej. "confirmado") sin
                    // el bloque completo de datos: se muestra directo, sin
                    // lógica de parpadeo por firma.
                    overlayManager.show(AlertState.SUCCESS, ScreenTextHolder.successMessage())
                } else if (signature != lastShownSignature) {
                    // Ruta/orden/total/leído/faltan distintos a lo último
                    // mostrado: es un código nuevo. Apagar, esperar un
                    // toque, prender con el dato nuevo.
                    lastShownSignature = signature
                    overlayManager.hide()
                    pollHandler.postDelayed({
                        overlayManager.show(AlertState.SUCCESS, ScreenTextHolder.successMessage())
                    }, BLINK_DELAY_MS)
                }
                // Si la firma es igual a la ya mostrada, no se toca nada.
            }
            AlertState.NONE -> {
                // No hacemos nada: el overlay queda como esté hasta que
                // haya un código nuevo o un error real.
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
        // removeCallbacksAndMessages para cancelar también un posible
        // postDelayed de "prender después del parpadeo" pendiente.
        pollHandler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() {
        // No-op
    }
}
