package com.gaston.handheldalert

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Tiempos y estado de la última lectura, para el botón de debug de MainActivity. */
object ReadStats {
    @Volatile var lastReadMs: Long = -1
    @Volatile var lastNodeCount: Int = 0
    @Volatile var lastTriggerToShowMs: Long = -1
    @Volatile var focusedFieldText: String? = null
    @Volatile var lastScanInput: String? = null
    // Avisos del lector Honeywell (Data Intent): cuántos llegaron y cuándo.
    @Volatile var scannerIntentCount: Int = 0
    @Volatile var lastScannerIntentAt: Long = 0 // System.currentTimeMillis()
    @Volatile var lastScannerData: String? = null
    // Últimas teclas que llegaron al servicio (para ver qué manda cada equipo).
    @Volatile var recentKeys: List<Int> = emptyList()
}

/** Resultado de una lectura de pantalla (se arma en el hilo lector). */
private class ScreenRead(
    val text: String,
    // Campo editable con el cursor: donde el lector escribe el código.
    val fieldKey: String?,
    val fieldText: String?
)

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
 * Además la ventana se cierra sola al escanear un código nuevo, para que no
 * quede mostrando el dato anterior mientras SAP responde, y se vuelve a
 * abrir cuando aparece la nueva combinación. Se detecta de dos formas:
 * - El código aparece en el campo con el cursor (el lector lo escribe ahí
 *   como si fuera teclado): ver [checkScanInput]. No depende del equipo.
 * - El gatillo físico de escaneo, si llega a Android como tecla (en la
 *   Zebra sí; en Honeywell el lector se lo queda): ver [onKeyEvent].
 * - El aviso que manda el lector Honeywell en cada lectura si se le activa
 *   "Data Intent" (sin apagar el Wedge, así el código se sigue escribiendo
 *   en SAP como siempre): ver [scannerReceiver].
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
    private var lastFieldKey: String? = null
    private var lastFieldText: String? = null

    private val readRunnable = Runnable {
        readQueued.set(false)
        val read = readScreenText() ?: return@Runnable
        mainHandler.post { applyScreenRead(read) }
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

    /**
     * Honeywell (CK65 y similares): el botón azul no llega a Android como
     * tecla — el servicio del lector se lo queda. Pero si en los ajustes del
     * lector se activa "Data Intent" con la acción
     * [DetectionConfig.SCAN_INTENT_ACTION], el lector avisa con un broadcast
     * cada vez que lee un código, además de escribirlo en SAP. Llega en el
     * hilo principal, apenas se lee el código y antes de que SAP responda.
     */
    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ReadStats.scannerIntentCount++
            ReadStats.lastScannerIntentAt = System.currentTimeMillis()
            ReadStats.lastScannerData = intent.getStringExtra("data")
            onNewScanStarted()
        }
    }
    private var scannerReceiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        readerThread = HandlerThread("HandheldAlertReader").apply { start() }
        readerHandler = Handler(readerThread.looper)
        readerHandler.post(pollLoop)
        mainHandler.postDelayed(updateCheck, FIRST_UPDATE_CHECK_MS)
        registerScannerReceiver()
    }

    private fun registerScannerReceiver() {
        if (scannerReceiverRegistered) return
        val filter = IntentFilter(DetectionConfig.SCAN_INTENT_ACTION).apply {
            // Si en el equipo se completa "Category" con DEFAULT, el intent
            // la trae, y un filtro sin esa categoría no lo recibiría.
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        // Exportado: el que lo manda es el servicio del lector (otra app).
        ContextCompat.registerReceiver(
            this, scannerReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        scannerReceiverRegistered = true
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
        ReadStats.recentKeys = (ReadStats.recentKeys + event.keyCode).takeLast(10)

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
            onNewScanStarted()
        }
        return false
    }

    /** Se está leyendo un código nuevo: cerrar hasta que llegue su combinación. */
    private fun onNewScanStarted() {
        lastTriggerAt = SystemClock.uptimeMillis()
        cancelPendingShow()
        overlayManager.hide()
    }

    private fun cancelPendingShow() {
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
    }

    /**
     * El lector escribe el código en el campo con el cursor, como un teclado.
     * Si ese mismo campo pasa a tener un valor nuevo (no vacío), es un
     * escaneo nuevo. Solo cuenta el campo con el cursor, y solo si es el
     * mismo que en la lectura anterior: así no cierran la ventana campos que
     * SAP actualiza solo (ej. "Cantidad Leída"), ni un campo con datos al que
     * se le pasa el cursor. Que el campo se vacíe (página recargada) no cuenta.
     */
    private fun checkScanInput(read: ScreenRead) {
        val value = read.fieldText?.trim().orEmpty()
        ReadStats.focusedFieldText = read.fieldText
        if (read.fieldKey != null && read.fieldKey == lastFieldKey &&
            value.isNotEmpty() && value != lastFieldText
        ) {
            ReadStats.lastScanInput = value
            onNewScanStarted()
        }
        lastFieldKey = read.fieldKey
        lastFieldText = value
    }

    /** Hilo lector. Devuelve lo visible del navegador, o null. */
    private fun readScreenText(): ScreenRead? {
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
        val field = FocusedField()
        val nodes = collectText(root, builder, field)
        root.recycle()

        ReadStats.lastReadMs = SystemClock.uptimeMillis() - start
        ReadStats.lastNodeCount = nodes
        if (builder.isEmpty()) return null
        return ScreenRead(builder.toString(), field.key, field.text)
    }

    /** Hilo principal: aplica una lectura y decide qué hacer con la ventana. */
    private fun applyScreenRead(read: ScreenRead) {
        checkScanInput(read)
        ScreenTextHolder.update(read.text)

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

    /** Campo editable con el cursor que encontró [collectText]. */
    private class FocusedField {
        var key: String? = null
        var text: String? = null
    }

    /**
     * Junta el texto visible bajo [node] y anota en [field] el campo editable
     * con el cursor. Devuelve cuántos nodos recorrió.
     */
    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        field: FocusedField,
        depth: Int = 0
    ): Int {
        if (depth > 40) return 0
        // Ojo: no seguir con nodos no visibles (ej. opciones ocultas de un
        // <select> colapsado) — si no, su texto se suma igual aunque no
        // aparezca en pantalla, y puede matchear un patrón por error.
        if (!node.isVisibleToUser) return 1
        val text = node.text
        if (!text.isNullOrBlank()) {
            out.append(text).append(' ')
        }
        if (node.isEditable && node.isFocused && field.key == null) {
            // Identidad del campo: id (si el WebView lo expone) + posición.
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            field.key = "${node.viewIdResourceName}@${bounds.flattenToString()}"
            field.text = text?.toString().orEmpty()
        }
        val desc = node.contentDescription
        if (!desc.isNullOrBlank()) {
            out.append(desc).append(' ')
        }
        var count = 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += collectText(child, out, field, depth + 1)
            child.recycle()
        }
        return count
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scannerReceiverRegistered) {
            unregisterReceiver(scannerReceiver)
            scannerReceiverRegistered = false
        }
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
