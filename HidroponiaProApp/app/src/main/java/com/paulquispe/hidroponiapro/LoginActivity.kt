package com.paulquispe.hidroponiapro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class LoginActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    // Usando IP de red local para comunicación transparente física/emulador
    private val BASE_URL = "http://192.168.2.108:8000"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val txtUsername = findViewById<EditText>(R.id.txt_username)
        val txtPassword = findViewById<EditText>(R.id.txt_password)
        val btnIngresar = findViewById<Button>(R.id.btn_ingresar)
        val btnIrRegistrar = findViewById<Button>(R.id.btn_ir_registrar)

        btnIngresar.setOnClickListener {
            val username = txtUsername.text.toString().trim()
            val password = txtPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, complete todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                ejecutarLogin(username, password)
            }
        }

        btnIrRegistrar.setOnClickListener {
            val username = txtUsername.text.toString().trim()
            val password = txtPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Escriba un usuario y contraseña para registrar", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                ejecutarRegistro(username, password)
            }
        }
    }

    private suspend fun ejecutarLogin(user: String, pass: String) {
        val formBody = FormBody.Builder()
            .add("username", user)
            .add("password", pass)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/api/login")
            .addHeader("Accept", "application/json")
            .post(formBody)
            .build()

        var loginExitoso = false
        var accessToken: String? = null

        try {
            // 📡 Procesamos la red estrictamente en segundo plano
            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    val jsonObject = JSONObject(responseData)
                    // Mapeamos de forma flexible si viene en minúsculas o mayúsculas del API
                    accessToken = when {
                        jsonObject.has("access_token") -> jsonObject.getString("access_token")
                        jsonObject.has("JWT_TOKEN") -> jsonObject.getString("JWT_TOKEN")
                        else -> null
                    }
                    if (accessToken != null) {
                        loginExitoso = true
                    }
                }
            }

            // 🚀 Una vez cerrada la conexión de red de forma segura, volvemos a la UI
            withContext(Dispatchers.Main) {
                if (loginExitoso && accessToken != null) {
                    // Persistencia segura local
                    val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
                    sharedPref.edit().apply {
                        putString("JWT_TOKEN", accessToken)
                        putString("USERNAME", user)
                        apply()
                    }

                    Toast.makeText(this@LoginActivity, "Autenticación Exitosa", Toast.LENGTH_SHORT).show()

                    // Salto limpio al Tablero SCADA
                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@LoginActivity, "Usuario o contraseña incorrectos", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@LoginActivity, "Error de conexión con el servidor SCADA", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun ejecutarRegistro(user: String, pass: String) {
        val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

        val jsonBody = JSONObject().apply {
            put("nombre", "Paul Quispe")
            put("username", user)
            put("password", pass)
        }.toString()

        val body = RequestBody.create(jsonMediaType, jsonBody)

        val request = Request.Builder()
            .url("$BASE_URL/api/usuarios/registrar")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        var registroExitoso = false
        var mensajeError: String? = null

        try {
            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string()
                if (response.isSuccessful) {
                    registroExitoso = true
                } else {
                    mensajeError = responseData?.let {
                        try { JSONObject(it).optString("detail") } catch(_: Exception) { "Error en backend" }
                    } ?: "Error de datos"
                }
            }

            withContext(Dispatchers.Main) {
                if (registroExitoso) {
                    Toast.makeText(this@LoginActivity, "🌱 ¡Usuario '$user' registrado con éxito!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@LoginActivity, "⚠️ No se pudo registrar: $mensajeError", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@LoginActivity, "Error de red al intentar registrar", Toast.LENGTH_LONG).show()
            }
        }
    }
}