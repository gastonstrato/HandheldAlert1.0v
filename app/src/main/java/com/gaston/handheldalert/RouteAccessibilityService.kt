package com.gaston.handheldalert

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

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
 * 2. La "firma" del resultado actual (ScreenTextHolder.currentSignature():
 *    ruta+orden+total+leído+faltan en Apertura de HU, o la fila completa de
 *    la tabla en Lectura Ruteador) cambia respecto a la última mostrada ->
 *    eso significa que se escaneó un código nuevo: se oculta la ventana, se
 *    espera [BLINK_DELAY_MS] y se vuelve a mostrar con los datos nuevos
 *    (con un fade-in, para que se note que es un dato nuevo).
 *
 * Cualquier otra lectura (texto sin patrones, o exactamente el mismo
 * resultado de siempre) no toca el overlay para nada.
 *
 * Además, apretar el gatillo físico de escaneo cierra la ventana (ver
 * [onKeyEvent]): mientras se lee el código siguiente queda cerrada, y se
 * vuelve a abrir sola cuando aparece la nueva combinación.
 */
class RouteAccessibilityService : AccessibilityService() {

    companion object {
        private const val POLL_INTERVAL_MS = 200L
        private const val BLINK_DELAY_MS = 200L
    }

    private val overlayManager by lazy { OverlayAlertManager(applicationContext) }
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastShownSignature: String? = null
    private var pendingShow: Runnable? = null

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

    /**
     * Gatillo físico de escaneo: cierra la ventana verde. Devuelve siempre
     * false para NO consumir la tecla — el escaneo tiene que seguir
     * funcionando igual que sin la app.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        if (DetectionConfig.learningScanKey) {
            DetectionConfig.learningScanKey = false
            DetectionConfig.saveScanKeyCode(this, event.keyCode)
            Toast.makeText(
                applicationContext,
                "Botón de escaneo guardado (código ${event.keyCode})",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        if (DetectionConfig.isScanKey(this, event.keyCode)) {
            cancelPendingShow()
            overlayManager.hide()
        }
        return false
    }

    private fun cancelPendingShow() {
        pendingShow?.let { pollHandler.removeCallbacks(it) }
        pendingShow = null
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
                cancelPendingShow()
                overlayManager.hide()
            }
            AlertState.SUCCESS -> {
                // Sin bloque de datos (matcheó una palabra suelta como
                // "confirmado"), la firma es el propio mensaje: así tampoco
                // se reabre sola en cada lectura después de cerrarla con el
                // gatillo.
                val message = ScreenTextHolder.successMessage()
                val signature = ScreenTextHolder.currentSignature() ?: "msg:$message"
                if (signature != lastShownSignature) {
                    // Combinación nueva = código nuevo. El mensaje se captura
                    // ACÁ y no dentro del postDelayed: ScreenTextHolder es
                    // estado global que el sondeo puede pisar en esos 200ms
                    // con una lectura vieja o parcial del WebView.
                    lastShownSignature = signature
                    cancelPendingShow()
                    if (overlayManager.currentState == AlertState.NONE) {
                        // Ya estaba cerrada (ej. por el gatillo): abrir directo.
                        overlayManager.show(AlertState.SUCCESS, message)
                    } else {
                        overlayManager.hide()
                        val show = Runnable {
                            pendingShow = null
                            overlayManager.show(AlertState.SUCCESS, message)
                        }
                        pendingShow = show
                        pollHandler.postDelayed(show, BLINK_DELAY_MS)
                    }
                }
                // Misma firma que la ya mostrada: no se toca nada (si se
                // cerró con el gatillo, queda cerrada hasta el próximo código).
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
