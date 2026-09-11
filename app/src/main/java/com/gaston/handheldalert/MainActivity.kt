package com.gaston.handheldalert

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gaston.handheldalert.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenWatchService::class.java).apply {
                    putExtra(ScreenWatchService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenWatchService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Captura iniciada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permiso de captura rechazado", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnAccessibilityPermission.setOnClickListener { openAccessibilitySettings() }
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        binding.btnStartCapture.setOnClickListener { requestScreenCapture() }
        binding.btnStopCapture.setOnClickListener {
            stopService(Intent(this, ScreenWatchService::class.java))
            refreshStatus()
        }
        binding.btnTestOverlay.setOnClickListener {
            OverlayAlertManager(this).show(
                AlertState.SUCCESS,
                "R8\nO:7 -total:33 -Leido:30 - Faltan:3"
            )
        }
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

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
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
        val calibrated = DetectionConfig.isCalibrated(this)
        val capturing = ScreenWatchService.isRunning()

        val status = buildString {
            append(if (overlayOk) "✔" else "✘").append(" Superposición\n")
            append(if (accessibilityOk) "✔" else "✘").append(" Accesibilidad\n")
            append(if (calibrated) "✔" else "✘").append(" Zona calibrada\n")
            append(if (capturing) "✔" else "✘").append(" Captura activa")
        }
        findViewById<TextView>(R.id.statusText).text = status
    }
}
