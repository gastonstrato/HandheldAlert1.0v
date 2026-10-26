package com.gaston.handheldalert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gaston.handheldalert.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var checkedForUpdateOnOpen = false
    private var updateInProgress = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnAccessibilityPermission.setOnClickListener { openAccessibilitySettings() }
        binding.btnLearnScanKey.setOnClickListener { startLearningScanKey() }
        binding.btnTestOverlay.setOnClickListener {
            OverlayAlertManager(this).show(
                AlertState.SUCCESS,
                "R8\nO:7 -total:33 -Leido:30 - Faltan:3"
            )
        }
        binding.btnDebugText.setOnClickListener { showLastReadText() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate(quietIfNone = false) }

        // Android 13+: sin este permiso no aparece el aviso de versión nueva.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Consulta la última release en GitHub. Al abrir la app solo avisa si
     * hay algo nuevo ([quietIfNone]); desde el botón también avisa si ya
     * está actualizada o si no se pudo consultar.
     */
    private fun checkForUpdate(quietIfNone: Boolean) {
        if (updateInProgress) return
        if (!quietIfNone) {
            Toast.makeText(this, "Buscando actualización…", Toast.LENGTH_SHORT).show()
        }
        Thread {
            val release = AppUpdater.fetchLatest()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    release == null -> if (!quietIfNone) {
                        Toast.makeText(this, "No se pudo consultar (¿hay internet?)", Toast.LENGTH_LONG).show()
                    }
                    !AppUpdater.isNewer(this, release) -> if (!quietIfNone) {
                        Toast.makeText(this, "Ya tenés la última versión", Toast.LENGTH_LONG).show()
                    }
                    else -> offerUpdate(release)
                }
            }
        }.start()
    }

    private fun offerUpdate(release: AppUpdater.Release) {
        AlertDialog.Builder(this)
            .setTitle("Hay una versión nueva")
            .setMessage("${release.name}\n\n¿Actualizar ahora? La configuración se mantiene.")
            .setPositiveButton("Actualizar") { _, _ -> startUpdate(release) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun startUpdate(release: AppUpdater.Release) {
        if (!AppUpdater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Falta un permiso")
                .setMessage(
                    "Para actualizarse, Handheld Alert necesita permiso para instalar apps. " +
                        "En la pantalla que se abre activá \"Permitir de esta fuente\", " +
                        "volvé y tocá de nuevo \"Buscar actualización\"."
                )
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(AppUpdater.installPermissionSettings(this))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        updateInProgress = true
        val progress = AlertDialog.Builder(this)
            .setTitle("Descargando actualización")
            .setMessage("0%")
            .setCancelable(false)
            .show()
        Thread {
            val apk = try {
                AppUpdater.download(this, release) { percent ->
                    runOnUiThread { progress.setMessage("$percent%") }
                }
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                updateInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (apk == null) {
                    Toast.makeText(this, "No se pudo descargar la actualización", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(AppUpdater.installIntent(this, apk))
                }
            }
        }.start()
    }

    /**
     * El código de tecla del gatillo varía según el modelo y la
     * configuración del equipo, así que se registra apretándolo: el
     * servicio de accesibilidad guarda la próxima tecla que llegue.
     */
    private fun startLearningScanKey() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Primero habilitá el servicio de accesibilidad", Toast.LENGTH_LONG).show()
            return
        }
        DetectionConfig.learningScanKey = true
        Toast.makeText(this, "Apretá ahora el botón de escaneo", Toast.LENGTH_LONG).show()
    }

    /**
     * Muestra el último texto leído del navegador y en qué color lo
     * clasificó, para poder confirmar en el momento (sin logs ni cable) qué
     * está leyendo la app cuando algo no cierra — ej. si dispara una alerta
     * que no corresponde, ver acá qué palabra la disparó.
     */
    private fun showLastReadText() {
        val text = ScreenTextHolder.lastFullScreenText.ifBlank { "(todavía no se leyó nada)" }
        val readMs = ReadStats.lastReadMs
        val triggerMs = ReadStats.lastTriggerToShowMs
        val timings = buildString {
            append("Lectura de pantalla: ")
            append(if (readMs >= 0) "$readMs ms (${ReadStats.lastNodeCount} elementos)" else "sin datos")
            append("\nGatillo → ventana: ")
            append(if (triggerMs >= 0) "$triggerMs ms (incluye la respuesta de SAP)" else "sin datos")
        }
        AlertDialog.Builder(this)
            .setTitle("Último texto leído: ${ScreenTextHolder.classify()}")
            .setMessage("$timings\n\n$text")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Una vez por apertura (no en cada vuelta desde ajustes o el instalador).
        if (!checkedForUpdateOnOpen) {
            checkedForUpdateOnOpen = true
            checkForUpdate(quietIfNone = true)
        }
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

        val scanKey = DetectionConfig.learnedScanKeyCode(this)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val status = buildString {
            append("Versión $versionName (build ${AppUpdater.installedBuild(this@MainActivity)})\n\n")
            append(if (overlayOk) "✔" else "✘").append(" Superposición\n")
            append(if (accessibilityOk) "✔" else "✘").append(" Accesibilidad\n")
            if (scanKey != null) {
                append("✔ Botón de escaneo (código $scanKey)")
            } else {
                append("– Botón de escaneo sin configurar (se prueban códigos comunes)")
            }
        }
        findViewById<TextView>(R.id.statusText).text = status
    }
}
