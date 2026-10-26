package com.gaston.handheldalert

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/** Tiempos de la última lectura, para el botón de debug de MainActivity. */
object ReadStats {
    @Volatile var lastReadMs: Long = -1
    @Volatile var lastNodeCount: Int = 0
    @Volatile var lastTriggerToShowMs: Long = -1
}

/**
 * Lee el árbol de accesibilidad de la ventana del navegador (Dolphin o Chrome)
 * y junta el texto visible en una sola cadena. De ahí ScreenTextHolder saca
 * el número de ruta y el resto del bloque de éxito.
 *
 * El overlay solo cambia de estado en dos casos concretos, no en cada lectura:
 *
 * 1. Aparece un error/aviso conocido -> se oculta la ventana y no se
 *    muestra nada (por ahora; el rojo se vuelve a sumar más adelante).
 * 2. La "firma" del resultado actual (ScreenTextHolder.currentSignature():
 *    ruta+orden+total+leído+faltan en Apertura de HU, o la fila completa de
 *    la tabla en Lectura Ruteador) cambia respecto a la última mostrada ->
 *    se escaneó un código nuevo: se muestra con los datos nuevos (fade-in).
 *
 * Además, apretar el gatillo físico de escaneo cierra la ventana (ver
 * [onKeyEvent]): queda cerrada mientras se lee el código siguiente y se
 * vuelve a abrir sola cuando aparece la nueva combinación.
 *
 * Hilos: recorrer el árbol es lo caro (en una TC25 puede llevar cientos de
 * ms), así que se hace en [readerThread] y nunca hay más de una lectura
 * encolada — antes cada evento de accesibilidad disparaba una lectura
 * completa en el hilo principal, y la ráfaga de eventos que manda Dolphin
 * al actualizar la página las apilaba, demorando la lectura con el dato
 * nuevo y el dibujo de la ventana. Todo el estado y el overlay viven en
 * el hilo principal.
 */
class RouteAccessibilityService : AccessibilityService() {

    companion object {
        private const val POLL_INTERVAL_MS = 100L
        private const val BLINK_DELAY_MS = 120L
        private const val FIRST_UPDATE_CHECK_MS = 60_000L
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private val overlayManager by lazy { OverlayAlertManager(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var readerThread: HandlerThread
    private lateinit var readerHandler: Handler
    private val readQueued = AtomicBoolean(false)

    // Solo se tocan en el hilo principal.
    private var lastShownSignature: String? = null
    private var pendingShow: Runnable? = null
    private var lastTriggerAt = 0L

    private val readRunnable = Runnable {
        readQueued.set(false)
        val text = readScreenText() ?: return@Runnable
        mainHandler.post { applyScreenText(text) }
    }

    private val pollLoop = object : Runnable {
        override fun run() {
            requestRead()
            readerHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // Avisa con una notificación si hay versión nueva (ver AppUpdater). La red
    // va en un hilo propio para no frenar ni el hilo principal ni el lector.
    private val updateCheck = object : Runnable {
        override fun run() {
            Thread { AppUpdater.checkAndNotify(applicationContext) }.start()
            mainHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        readerThread = HandlerThread("HandheldAlertReader").apply { start() }
        readerHandler = Handler(readerThread.looper)
        readerHandler.post(pollLoop)
        mainHandler.postDelayed(updateCheck, FIRST_UPDATE_CHECK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isMonitoredPackage(pkg)) return
        requestRead()
    }

    /** Encola una lectura, salvo que ya haya una esperando. */
    private fun requestRead() {
        if (!::readerHandler.isInitialized) return
        if (readQueued.compareAndSet(false, true)) {
            readerHandler.post(readRunnable)
        }
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
            lastTriggerAt = SystemClock.uptimeMillis()
            cancelPendingShow()
            overlayManager.hide()
        }
        return false
    }

    private fun cancelPendingShow() {
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
    }

    /** Hilo lector. Devuelve el texto visible del navegador, o null. */
    private fun readScreenText(): String? {
        val start = SystemClock.uptimeMillis()
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        val pkg = root.packageName?.toString()
        if (pkg == null || !isMonitoredPackage(pkg)) {
            root.recycle()
            return null
        }

        val builder = StringBuilder()
        val nodes = collectText(root, builder)
        root.recycle()

        ReadStats.lastReadMs = SystemClock.uptimeMillis() - start
        ReadStats.lastNodeCount = nodes
        return if (builder.isEmpty()) null else builder.toString()
    }

    /** Hilo principal: clasifica el texto leído y decide qué hacer con la ventana. */
    private fun applyScreenText(text: String) {
        ScreenTextHolder.update(text)

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
                    // ACÁ y no al mostrar: una lectura posterior puede pisar
                    // ScreenTextHolder con un estado viejo o parcial del
                    // WebView antes de que se ejecute el show demorado.
                    lastShownSignature = signature
                    cancelPendingShow()
                    if (overlayManager.currentState == AlertState.NONE) {
                        // Ya estaba cerrada (ej. por el gatillo): abrir directo.
                        showSuccess(message)
                    } else {
                        overlayManager.hide()
                        val show = Runnable {
                            pendingShow = null
                            showSuccess(message)
                        }
                        pendingShow = show
                        mainHandler.postDelayed(show, BLINK_DELAY_MS)
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

    private fun showSuccess(message: String) {
        overlayManager.show(AlertState.SUCCESS, message)
        if (lastTriggerAt > 0) {
            ReadStats.lastTriggerToShowMs = SystemClock.uptimeMillis() - lastTriggerAt
            lastTriggerAt = 0
        }
    }

    private fun isMonitoredPackage(pkg: String): Boolean {
        // Por defecto Dolphin/Chrome; si el usuario usa otro navegador,
        // puede sumar su paquete en DetectionConfig.DEFAULT_BROWSER_PACKAGES.
        return DetectionConfig.DEFAULT_BROWSER_PACKAGES.contains(pkg)
    }

    /** Junta el texto visible bajo [node]. Devuelve cuántos nodos recorrió. */
    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int = 0): Int {
        if (depth > 40) return 0
        // Ojo: no seguir con nodos no visibles (ej. opciones ocultas de un
        // <select> colapsado) — si no, su texto se suma igual aunque no
        // aparezca en pantalla, y puede matchear un patrón por error.
        if (!node.isVisibleToUser) return 1
        val text = node.text
        if (!text.isNullOrBlank()) {
            out.append(text).append(' ')
        }
        val desc = node.contentDescription
        if (!desc.isNullOrBlank()) {
            out.append(desc).append(' ')
        }
        var count = 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += collectText(child, out, depth + 1)
            child.recycle()
        }
        return count
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (::readerThread.isInitialized) {
            readerHandler.removeCallbacksAndMessages(null)
            readerThread.quitSafely()
        }
    }

    override fun onInterrupt() {
        // No-op
    }
}
