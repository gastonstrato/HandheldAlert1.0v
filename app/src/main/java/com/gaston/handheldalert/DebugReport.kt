package com.gaston.handheldalert

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arma el reporte de debug (lo mismo que muestra el diálogo, más equipo,
 * versión y permisos) y lo manda al Google Doc de debug a través de su
 * Apps Script publicado como aplicación web (ver tools/debug-google-docs.gs).
 * Con el link del documento solo no alcanza: escribir en un Doc requiere
 * iniciar sesión en Google; el script corre con la cuenta del dueño y recibe
 * el texto por POST sin credenciales en la handheld.
 */
object DebugReport {

    private const val PREFS = "handheld_alert_prefs"
    private const val KEY_WEBAPP_URL = "debug_webapp_url"
    private const val MAX_SCREEN_TEXT = 6000

    fun timingsAndFields(): String = buildString {
        val readMs = ReadStats.lastReadMs
        val triggerMs = ReadStats.lastTriggerToShowMs
        append("Lectura de pantalla: ")
        append(if (readMs >= 0) "$readMs ms (${ReadStats.lastNodeCount} elementos)" else "sin datos")
        append("\nEscaneo → ventana: ")
        append(if (triggerMs >= 0) "$triggerMs ms (incluye la respuesta de SAP)" else "sin datos")
        append("\nCampo con el cursor: ")
        val field = ReadStats.focusedFieldText
        append(if (field == null) "(no se detecta ninguno)" else "\"$field\"")
        append("\nÚltimo código detectado: ")
        append(ReadStats.lastScanInput ?: "(ninguno todavía)")
        append("\nAviso del lector (Data Intent): ")
        append(scannerIntentSummary())
        append("\nÚltimas teclas recibidas: ")
        val keys = ReadStats.recentKeys
        append(if (keys.isEmpty()) "(ninguna)" else keys.joinToString(", "))
    }

    fun scannerIntentSummary(): String {
        val count = ReadStats.scannerIntentCount
        if (count == 0) return "no llegó ninguno"
        val secs = (System.currentTimeMillis() - ReadStats.lastScannerIntentAt) / 1000
        val data = ReadStats.lastScannerData?.let { " — código \"$it\"" }.orEmpty()
        return "$count recibidos, el último hace $secs s$data"
    }

    fun build(context: Context, overlayOk: Boolean, accessibilityOk: Boolean): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val scanKey = DetectionConfig.learnedScanKeyCode(context)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val screenText = ScreenTextHolder.lastFullScreenText
        return buildString {
            append("Handheld Alert — debug ($now)\n")
            append("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
            append("App: ${info.versionName} (build ${AppUpdater.installedBuild(context)})\n")
            append("Superposición: ${if (overlayOk) "sí" else "NO"} | ")
            append("Accesibilidad: ${if (accessibilityOk) "sí" else "NO"} | ")
            append("Botón de escaneo: ${scanKey?.let { "código $it" } ?: "sin configurar"}\n")
            append(timingsAndFields())
            append("\nClasificación: ${ScreenTextHolder.classify()}")
            append("\nFirma actual: ${ScreenTextHolder.currentSignature() ?: "(ninguna)"}")
            append("\n\nTexto leído:\n")
            append(
                if (screenText.isBlank()) "(todavía no se leyó nada)"
                else screenText.take(MAX_SCREEN_TEXT)
            )
        }
    }

    fun savedUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WEBAPP_URL, null)

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WEBAPP_URL, url)
            .apply()
    }

    fun isValidUrl(url: String): Boolean =
        url.startsWith("https://script.google.com/") && url.contains("/exec")

    sealed class SendResult {
        object Ok : SendResult()
        object NotPublic : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    /** Red: llamar fuera del hilo principal. */
    fun send(url: String, text: String): SendResult {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                // Apps Script ejecuta doPost y recién después redirige a la
                // respuesta; la redirección no hace falta seguirla. Sí sirve
                // mirar a dónde va: si va al login de Google, el script no
                // está publicado para "Cualquier usuario".
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location").orEmpty()
                when {
                    code in 300..399 && location.contains("accounts.google.com") -> SendResult.NotPublic
                    code in 200..399 -> SendResult.Ok
                    else -> SendResult.Failed("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            SendResult.Failed(e.javaClass.simpleName)
        }
    }
}
