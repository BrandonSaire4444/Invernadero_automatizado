package com.paulquispe.hidroponiapro

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

class RegistrarCultivoActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val BASE_URL = "http://192.168.2.108:8000"
    private var tokenJwt: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🛡️ Inflamos la vista del formulario
        setContentView(R.layout.activity_registrar_cultivo)

        // Extraemos token para la cabecera de autorización HTTP
        val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
        tokenJwt = sharedPref.getString("JWT_TOKEN", "") ?: ""

        // Inicialización minuciosa de componentes mapeados en Cassandra
        val txtIdCultivo = findViewById<EditText>(R.id.txtIdCultivo)
        val txtNombreVerdura = findViewById<EditText>(R.id.txtNombreVerdura)
        val txtTempMin = findViewById<EditText>(R.id.txtTempMin)
        val txtTempMax = findViewById<EditText>(R.id.txtTempMax)
        val txtHumMin = findViewById<EditText>(R.id.txtHumMin)
        val txtHumMax = findViewById<EditText>(R.id.txtHumMax)
        val txtPhMin = findViewById<EditText>(R.id.txtPhMin)
        val txtPhMax = findViewById<EditText>(R.id.txtPhMax)
        val txtLuzMin = findViewById<EditText>(R.id.txtLuzMin)
        val txtLuzMax = findViewById<EditText>(R.id.txtLuzMax)

        findViewById<Button>(R.id.btnGuardarCultivo).setOnClickListener {
            val idCultivo = txtIdCultivo.text.toString().trim()
            val nombreVerdura = txtNombreVerdura.text.toString().trim()

            // Validación de campos vacíos para evitar inconsistencias de red
            if (idCultivo.isEmpty() || nombreVerdura.isEmpty() ||
                txtTempMin.text.isEmpty() || txtTempMax.text.isEmpty() ||
                txtHumMin.text.isEmpty() || txtHumMax.text.isEmpty() ||
                txtPhMin.text.isEmpty() || txtPhMax.text.isEmpty() ||
                txtLuzMin.text.isEmpty() || txtLuzMax.text.isEmpty()) {

                Toast.makeText(this, "⚠️ Todos los campos son obligatorios", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Construcción del JSON con tipos numéricos correctos (float)
            val jsonPayload = JSONObject().apply {
                put("id_cultivo", idCultivo)
                put("nombre_verdura", nombreVerdura)
                put("temp_min", txtTempMin.text.toString().toFloat())
                put("temp_max", txtTempMax.text.toString().toFloat())
                put("hum_min", txtHumMin.text.toString().toFloat())
                put("hum_max", txtHumMax.text.toString().toFloat())
                put("ph_min", txtPhMin.text.toString().toFloat())
                put("ph_max", txtPhMax.text.toString().toFloat())
                put("luz_min", txtLuzMin.text.toString().toFloat())
                put("luz_max", txtLuzMax.text.toString().toFloat())
            }.toString()

            // Transmisión asíncrona hacia FastAPI para no congelar la UI
            CoroutineScope(Dispatchers.IO).launch {
                enviarCultivoAlServidor(jsonPayload)
            }
        }

        // Botón Cancelar: Cierra la ventana actual y vuelve al SCADA de inmediato
        findViewById<Button>(R.id.btnCancelarCultivo).setOnClickListener {
            finish()
        }
    }

    private suspend fun enviarCultivoAlServidor(json: String) {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = RequestBody.create(mediaType, json)

        val request = Request.Builder()
            .url("$BASE_URL/api/cultivos/registrar")
            .addHeader("Authorization", "Bearer $tokenJwt")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@RegistrarCultivoActivity, "🌱 Cultivo guardado e indexado", Toast.LENGTH_LONG).show()
                        finish() // Retorno exitoso
                    } else {
                        Toast.makeText(this@RegistrarCultivoActivity, "⚠️ Error en API: Cód ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RegistrarCultivoActivity, "❌ Falla de red: No se conectó al backend", Toast.LENGTH_LONG).show()
            }
        }
    }
}