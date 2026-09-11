package com.gaston.handheldalert

import android.content.Context
import android.graphics.RectF

/**
 * Guarda la región del ícono (error / tilde verde) como fracciones (0f..1f)
 * del ancho/alto de pantalla, para que funcione en cualquier resolución del handheld.
 */
object DetectionConfig {

    private const val PREFS = "handheld_alert_prefs"
    private const val KEY_LEFT = "icon_left"
    private const val KEY_TOP = "icon_top"
    private const val KEY_RIGHT = "icon_right"
    private const val KEY_BOTTOM = "icon_bottom"
    private const val KEY_BROWSER_PACKAGE = "browser_package"

    // Paquetes de navegador soportados por defecto (Dolphin y Chrome).
    // El usuario puede agregar el suyo si usa otro navegador en el handheld.
    val DEFAULT_BROWSER_PACKAGES = setOf(
        "mobi.mgeek.TunnyBrowser", // Dolphin Browser
        "com.android.chrome",
        "com.chrome.beta",
        "com.google.android.apps.chrome"
    )

    fun saveIconRegion(context: Context, rect: RectF) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_LEFT, rect.left)
            .putFloat(KEY_TOP, rect.top)
            .putFloat(KEY_RIGHT, rect.right)
            .putFloat(KEY_BOTTOM, rect.bottom)
            .apply()
    }

    fun getIconRegion(context: Context): RectF? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LEFT)) return null
        return RectF(
            prefs.getFloat(KEY_LEFT, 0f),
            prefs.getFloat(KEY_TOP, 0f),
            prefs.getFloat(KEY_RIGHT, 1f),
            prefs.getFloat(KEY_BOTTOM, 1f)
        )
    }

    fun isCalibrated(context: Context): Boolean = getIconRegion(context) != null
}
