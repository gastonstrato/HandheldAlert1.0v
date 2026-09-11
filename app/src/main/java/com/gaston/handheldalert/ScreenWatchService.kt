package com.gaston.handheldalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano que captura la pantalla cada ~700ms, recorta la
 * región calibrada del ícono y decide si mostrar la alerta roja (error) o
 * verde (tilde/ruta ok), usando el texto que RouteAccessibilityService fue
 * leyendo del navegador.
 */
class ScreenWatchService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_watch_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_INTERVAL_MS = 700L
        private var isRunning = false
        fun isRunning() = isRunning
    }

    private lateinit var mediaProjection: MediaProjection
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var overlayManager: OverlayAlertManager
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var lastState = AlertState.NONE
    private var stableCount = 0

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayAlertManager(applicationContext)
        handlerThread = HandlerThread("ScreenWatchThread").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()
        isRunning = true
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HandheldAlertCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        handler.post(captureLoop)
    }

    private val captureLoop = object : Runnable {
        override fun run() {
            try {
                analyzeLatestFrame()
            } catch (_: Exception) {
                // Si falla un frame, seguimos con el próximo; no queremos
                // que un error puntual tire abajo el servicio.
            }
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    private fun analyzeLatestFrame() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }

        val region = DetectionConfig.getIconRegion(applicationContext)
        if (region == null) {
            bitmap.recycle()
            return // Todavía no se calibró la zona del ícono.
        }

        val cropLeft = (region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val cropTop = (region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val cropWidth = ((region.right - region.left) * bitmap.width).toInt()
            .coerceIn(1, bitmap.width - cropLeft)
        val cropHeight = ((region.bottom - region.top) * bitmap.height).toInt()
            .coerceIn(1, bitmap.height - cropTop)

        val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        val detected = IconColorAnalyzer.classify(cropped)
        cropped.recycle()
        bitmap.recycle()

        handleDetection(detected)
    }

    private fun handleDetection(detected: AlertState) {
        // Debounce: pedimos 2 lecturas seguidas iguales antes de actuar,
        // para no parpadear por un frame de transición.
        if (detected == lastState) {
            stableCount++
        } else {
            lastState = detected
            stableCount = 1
        }

        if (stableCount != 2) return

        when (detected) {
            AlertState.NONE -> overlayManager.hide()
            AlertState.ERROR -> overlayManager.show(
                AlertState.ERROR,
                ScreenTextHolder.lastFullScreenText.ifBlank { "ERROR" }.take(80)
            )
            AlertState.WARNING -> overlayManager.show(
                AlertState.WARNING,
                ScreenTextHolder.lastFullScreenText.ifBlank { "AVISO" }.take(80)
            )
            AlertState.SUCCESS -> overlayManager.show(
                AlertState.SUCCESS,
                ScreenTextHolder.successMessage()
            )
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Monitoreo de pantalla", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Handheld Alert activo")
            .setContentText("Monitoreando la pantalla del navegador")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        virtualDisplay?.release()
        imageReader?.close()
        if (::mediaProjection.isInitialized) mediaProjection.stop()
        overlayManager.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
