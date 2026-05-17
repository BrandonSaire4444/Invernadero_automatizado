package com.paulquispe.hidroponiapro // ⚠️ Asegúrate de que este paquete coincida exactamente con el tuyo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class RegistroUsuarioActivity : AppCompatActivity() {

    private lateinit var txtNombre: EditText
    private lateinit var txtUsername: EditText
    private lateinit var txtPassword: EditText
    private lateinit var btnRegistrar: Button

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registro_usuario)

        txtNombre = findViewById(R.id.txtRegistroNombre)
        txtUsername = findViewById(R.id.txtRegistroUsername)
        txtPassword = findViewById(R.id.txtRegistroPassword)
        btnRegistrar = findViewById(R.id.btnEnviarRegistro)

        btnRegistrar.setOnClickListener {
            val nombre = txtNombre.text.toString().trim()
            val username = txtUsername.text.toString().trim()
            val password = txtPassword.text.toString().trim()

            if (nombre.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, llene todos los campos", Toast.LENGTH_SHORT).show()
            } else {
                enviarRegistroAlBackend(nombre, username, password)
            }
        }
    }

    private fun enviarRegistroAlBackend(nombre: String, username: String, password: String) {
        // Usa 10.0.2.2 si usas el emulador de Android Studio, o la IP local de tu LMDE 7 si usas celular real
        val url = "http://10.0.2.2:8000/api/usuarios/registrar"

        val jsonBody = JSONObject().apply {
            put("nombre", nombre)
            put("username", username)
            put("password", password)
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@RegistroUsuarioActivity, "Error de red: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respuestaString = response.body?.string()

                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@RegistroUsuarioActivity, "¡Usuario registrado en Cassandra!", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        try {
                            val jsonError = JSONObject(respuestaString ?: "{}")
                            val detalle = jsonError.optString("detail", "Error en el servidor")
                            Toast.makeText(this@RegistroUsuarioActivity, detalle, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@RegistroUsuarioActivity, "Error: Código ${response.code}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }
}