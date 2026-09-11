package com.gaston.handheldalert

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gaston.handheldalert.databinding.ActivityCalibrationBinding

/**
 * Pantalla de calibración: el usuario deja el navegador mostrando la
 * pantalla de ejemplo (con el error o la ruta) detrás, vuelve acá y marca
 * con el dedo el rectángulo donde aparece el ícono. Esa zona (en % de
 * pantalla) es la que después analiza ScreenWatchService en cada frame.
 *
 * Nota: esta pantalla no muestra en vivo lo que hay debajo (Android no lo
 * permite sin captura), así que se usa como guía visual sobre las
 * proporciones de la pantalla del handheld: conviene mirar el navegador,
 * tomar nota aproximada de dónde está el ícono (por ej. "arriba a la
 * izquierda, ocupa el 10% del ancho") y trasladarlo acá arrastrando.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSaveCalibration.setOnClickListener {
            val region = binding.selectionOverlay.normalizedSelection()
            if (region.width() < 0.01f || region.height() < 0.01f) {
                Toast.makeText(this, "Marcá un rectángulo primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DetectionConfig.saveIconRegion(this, region)
            Toast.makeText(this, "Zona guardada", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
