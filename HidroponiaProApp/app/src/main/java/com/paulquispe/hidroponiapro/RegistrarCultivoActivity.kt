package com.paulquispe.hidroponiapro

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegistrarCultivoActivity : AppCompatActivity() {

    private var tokenJwt: String = ""

    // Componentes del Formulario de Registro
    private lateinit var etNombreVerdura: EditText
    private lateinit var etTempMin: EditText
    private lateinit var etTempMax: EditText
    private lateinit var etHumMin: EditText
    private lateinit var etHumMax: EditText
    private lateinit var etPhMin: EditText
    private lateinit var etPhMax: EditText
    private lateinit var etLuzMin: EditText
    private lateinit var etLuzMax: EditText
    private lateinit var btnGuardar: Button
    private lateinit var btnCancelar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🛡️ Inflamos la vista del formulario
        setContentView(R.layout.activity_registrar_cultivo)

        // Extraemos token de autenticación por si tu backend lo requiere en las cabeceras
        val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
        tokenJwt = sharedPref.getString("JWT_TOKEN", "") ?: ""

        // Inicialización minuciosa de componentes
        inicializarVistas()

        // Adjuntar el validador predictivo en tiempo real a los campos numéricos
        configurarValidadorTiempoReal()

        // Acción del botón Guardar mediante el motor centralizado de Retrofit
        btnGuardar.setOnClickListener {
            ejecutarRegistroDeCultivo()
        }

        // Botón Cancelar: Cierra la ventana actual y vuelve al SCADA de inmediato
        btnCancelar.setOnClickListener {
            finish()
        }
    }

    private fun inicializarVistas() {
        etNombreVerdura = findViewById(R.id.txtNombreVerdura)
        etTempMin = findViewById(R.id.txtTempMin)
        etTempMax = findViewById(R.id.txtTempMax)
        etHumMin = findViewById(R.id.txtHumMin)
        etHumMax = findViewById(R.id.txtHumMax)
        etPhMin = findViewById(R.id.txtPhMin)
        etPhMax = findViewById(R.id.txtPhMax)
        etLuzMin = findViewById(R.id.txtLuzMin)
        etLuzMax = findViewById(R.id.txtLuzMax)
        btnGuardar = findViewById(R.id.btnGuardarCultivo)
        btnCancelar = findViewById(R.id.btnCancelarCultivo)
    }

    private fun configurarValidadorTiempoReal() {
        val validador = FieldValidator()
        etNombreVerdura.addTextChangedListener(validador)
        etTempMin.addTextChangedListener(validador)
        etTempMax.addTextChangedListener(validador)
        etHumMin.addTextChangedListener(validador)
        etHumMax.addTextChangedListener(validador)
        etPhMin.addTextChangedListener(validador)
        etPhMax.addTextChangedListener(validador)
        etLuzMin.addTextChangedListener(validador)
        etLuzMax.addTextChangedListener(validador)
    }

    // 🚀 ENVÍO DE DATOS OPTIMIZADO CON RETROFIT (Ya no construye JSONs manuales en String)
    private fun ejecutarRegistroDeCultivo() {
        // Empaquetamos los datos de forma segura previniendo excepciones de casteo de texto a float
        val nuevoCultivo = CultivoRegistro(
            nombre_verdura = etNombreVerdura.text.toString().trim(),
            temp_min = etTempMin.text.toString().toFloatOrNull() ?: 0f,
            temp_max = etTempMax.text.toString().toFloatOrNull() ?: 0f,
            hum_min = etHumMin.text.toString().toFloatOrNull() ?: 0f,
            hum_max = etHumMax.text.toString().toFloatOrNull() ?: 0f,
            ph_min = etPhMin.text.toString().toFloatOrNull() ?: 0f,
            ph_max = etPhMax.text.toString().toFloatOrNull() ?: 0f,
            luz_min = etLuzMin.text.toString().toFloatOrNull() ?: 0f,
            luz_max = etLuzMax.text.toString().toFloatOrNull() ?: 0f
        )

        // Lanzamos la corrutina utilizando el ciclo de vida nativo de la Actividad (Evita fugas de memoria)
        lifecycleScope.launch {
            try {
                // Ejecutamos la petición HTTP en el hilo de fondo (Dispatchers.IO) utilizando RetrofitClient
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApiService(tokenJwt).registrarCultivo(nuevoCultivo)
                }

                if (response.isSuccessful) {
                    Toast.makeText(this@RegistrarCultivoActivity, "🌱 Cultivo guardado e indexado", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish() // Retorno exitoso a la pantalla principal
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Error de validación en la API"
                    Toast.makeText(this@RegistrarCultivoActivity, "⚠️ Error en API: $errorMsg", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RegistrarCultivoActivity, "❌ Falla de red: No se pudo conectar al backend", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =============================================================================
    // VALIDACIÓN PREDICTIVA COHERENTE (Local)
    // =============================================================================
    inner class FieldValidator : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val nombre = etNombreVerdura.text.toString().trim()

            val tMin = etTempMin.text.toString().toFloatOrNull()
            val tMax = etTempMax.text.toString().toFloatOrNull()
            val hMin = etHumMin.text.toString().toFloatOrNull()
            val hMax = etHumMax.text.toString().toFloatOrNull()
            val pMin = etPhMin.text.toString().toFloatOrNull()
            val pMax = etPhMax.text.toString().toFloatOrNull()
            val lMin = etLuzMin.text.toString().toFloatOrNull()
            val lMax = etLuzMax.text.toString().toFloatOrNull()

            // Evaluación lógica de consistencia analítica
            val nombreValido = nombre.isNotEmpty()
            val tempValida = tMin != null && tMax != null && tMin < tMax
            val humValida = hMin != null && hMax != null && hMin < hMax
            val phValido = pMin != null && pMax != null && pMin < pMax
            val luzValida = lMin != null && lMax != null && lMin < lMax

            // Inyección visual de advertencias directas en la caja de texto
            if (tMin != null && tMax != null && tMin >= tMax) etTempMin.error = "El mínimo debe ser menor al máximo"
            if (hMin != null && hMax != null && hMin >= hMax) etHumMin.error = "El mínimo debe ser menor al máximo"
            if (pMin != null && pMax != null && pMin >= pMax) etPhMin.error = "El mínimo debe ser menor al máximo"
            if (lMin != null && lMax != null && lMin >= lMax) etLuzMin.error = "El mínimo debe ser menor al máximo"

            // El botón se habilita de forma automatizada únicamente si los datos son coherentes
            btnGuardar.isEnabled = nombreValido && tempValida && humValida && phValido && luzValida
        }
        override fun afterTextChanged(s: Editable?) {}
    }
}