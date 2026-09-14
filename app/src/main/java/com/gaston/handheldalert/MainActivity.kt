package com.gaston.handheldalert

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gaston.handheldalert.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnAccessibilityPermission.setOnClickListener { openAccessibilitySettings() }
        binding.btnTestOverlay.setOnClickListener {
            OverlayAlertManager(this).show(
                AlertState.SUCCESS,
                "R8\nO:7 -total:33 -Leido:30 - Faltan:3"
            )
        }
        binding.btnDebugText.setOnClickListener { showLastReadText() }
    }

    /**
     * Muestra el último texto leído del navegador y en qué color lo
     * clasificó, para poder confirmar en el momento (sin logs ni cable) qué
     * está leyendo la app cuando algo no cierra — ej. si dispara una alerta
     * que no corresponde, ver acá qué palabra la disparó.
     */
    private fun showLastReadText() {
        val text = ScreenTextHolder.lastFullScreenText.ifBlank { "(todavía no se leyó nada)" }
        AlertDialog.Builder(this)
            .setTitle("Último texto leído: ${ScreenTextHolder.classify()}")
            .setMessage(text)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Ya está habilitado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${RouteAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityServiceEnabled()

        val status = buildString {
            append(if (overlayOk) "✔" else "✘").append(" Superposición\n")
            append(if (accessibilityOk) "✔" else "✘").append(" Accesibilidad")
        }
        findViewById<TextView>(R.id.statusText).text = status
    }
}
