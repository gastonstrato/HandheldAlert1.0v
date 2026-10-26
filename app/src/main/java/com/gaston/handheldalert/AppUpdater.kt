package com.gaston.handheldalert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca versiones nuevas en las releases de GitHub del repo (público, sin
 * credenciales), baja el APK y abre el instalador de Android. La instalación
 * siempre pide un toque en "Instalar": Android no deja que una app común se
 * actualice sola sin confirmación.
 *
 * Cada release tiene tag "v<versión>-build<N>", y N es el mismo número que
 * el versionCode del APK, así que comparar N con el versionCode instalado
 * dice si hay algo nuevo.
 */
object AppUpdater {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/gastonstrato/HandheldAlert1.0v/releases/latest"
    private const val APK_ASSET_NAME = "HandheldAlert.apk"
    private val BUILD_IN_TAG = Regex("-build(\\d+)$")

    private const val PREFS = "handheld_alert_prefs"
    private const val KEY_LAST_NOTIFIED_BUILD = "last_notified_update_build"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2001

    data class Release(val build: Long, val name: String, val apkUrl: String)

    fun installedBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    /** Red: llamar fuera del hilo principal. Null si no se pudo consultar. */
    fun fetchLatest(): Release? {
        return try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val build = BUILD_IN_TAG.find(json.getString("tag_name"))
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return null
                val assets = json.getJSONArray("assets")
                val apkUrl = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == APK_ASSET_NAME }
                    ?.getString("browser_download_url") ?: return null
                Release(build, json.optString("name", "build $build"), apkUrl)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isNewer(context: Context, release: Release): Boolean =
        release.build > installedBuild(context)

    /** Red: llamar fuera del hilo principal. [onProgress] recibe 0..100. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, APK_ASSET_NAME)
        // La URL de GitHub redirige al almacenamiento de archivos (https ->
        // https); HttpURLConnection sigue esa redirección solo.
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return target
    }

    /** Android 8+: la app necesita permiso explícito para instalar APKs. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Chequeo en segundo plano (lo llama el servicio de accesibilidad, que
     * es lo que está siempre corriendo): si hay una versión nueva que todavía
     * no se avisó, deja una notificación que abre la app para actualizar.
     */
    fun checkAndNotify(context: Context) {
        val release = fetchLatest() ?: return
        if (!isNewer(context, release)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_LAST_NOTIFIED_BUILD, 0) == release.build) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Actualización de Handheld Alert")
            .setContentText("${release.name} disponible. Tocá para instalar.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            prefs.edit().putLong(KEY_LAST_NOTIFIED_BUILD, release.build).apply()
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones (Android 13+): queda el chequeo
            // al abrir la app.
        }
    }
}
