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
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class LoginActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    // ⚙️ Al usar teléfono físico por cable USB + 'adb reverse',
    // el dispositivo mapea el puerto directo al localhost de tu Linux Mint.
    private val BASE_URL = "http://127.0.0.1:8000"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val txtUsername = findViewById<EditText>(R.id.txt_username)
        val txtPassword = findViewById<EditText>(R.id.txt_password)
        val btnIngresar = findViewById<Button>(R.id.btn_ingresar)
        val btnIrRegistrar = findViewById<Button>(R.id.btn_ir_registrar) // 🟢 Vinculado al nuevo botón del XML

        // 1. LÓGICA PARA INICIAR SESIÓN (AUTENTICAR)
        btnIngresar.setOnClickListener {
            val username = txtUsername.text.toString().trim()
            val password = txtPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, complete todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Ejecutamos la petición en un hilo seguro de fondo usando Corrutinas
            CoroutineScope(Dispatchers.IO).launch {
                ejecutarLogin(username, password)
            }
        }

        // 2. LÓGICA PARA REGISTRAR NUEVO USUARIO DIRECTO EN CASSANDRA
        btnIrRegistrar.setOnClickListener {
            val username = txtUsername.text.toString().trim()
            val password = txtPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Escriba un usuario y contraseña para registrar", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Ejecutamos el registro en segundo plano
            CoroutineScope(Dispatchers.IO).launch {
                ejecutarRegistro(username, password)
            }
        }
    }

    private suspend fun ejecutarLogin(user: String, pass: String) {
        // Construimos el cuerpo en formato Form-Data requerido por OAuth2PasswordRequestForm de FastAPI
        val formBody = FormBody.Builder()
            .add("username", user)
            .add("password", pass)
            .build()

        // 🛡️ Añadimos cabeceras explísitas para blindar la comunicación
        val request = Request.Builder()
            .url("$BASE_URL/api/login")
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Accept", "application/json")
            .post(formBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseData != null) {
                        // Parseamos la respuesta para obtener el Token JWT
                        val jsonObject = JSONObject(responseData)
                        val accessToken = jsonObject.getString("access_token")

                        // Persistencia: Guardamos el token en SharedPreferences
                        val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            putString("JWT_TOKEN", accessToken)
                            putString("USERNAME", user)
                            apply()
                        }

                        Toast.makeText(this@LoginActivity, "Autenticación Exitosa", Toast.LENGTH_SHORT).show()

                        // --- PASO A LA SIGUIENTE PANTALLA ---
                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish() // Cerramos el Login para liberar memoria
                    } else {
                        // Error controlado: Credenciales inválidas o rechazo del backend (Ej: 401 Unauthorized)
                        Toast.makeText(this@LoginActivity, "Usuario o contraseña incorrectos", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: IOException) {
            // Error físico: El cable se desconectó, el puerto no está revertido o el servidor está apagado
            withContext(Dispatchers.Main) {
                Toast.makeText(this@LoginActivity, "Error de conexión con el servidor SCADA", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 🟢 NUEVA FUNCIÓN: Envía un JSON nativo al endpoint público de registro en FastAPI
    // 🟢 FUNCIÓN DE REGISTRO OPTIMIZADA CON LA NUEVA SINTAXIS DE OKHTTP
    private suspend fun ejecutarRegistro(user: String, pass: String) {
        // Usa la función de extensión moderna de Kotlin para OkHttp3
        val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

        // Estructura JSON que mapea directo al modelo Pydantic "UsuarioRegistro" de tu FastAPI
        val jsonBody = JSONObject().apply {
            put("nombre", "Paul Quispe")
            put("username", user)
            put("password", pass)
        }.toString()

        // Verificamos que el MediaType no sea nulo antes de enviarlo
        val body = RequestBody.create(jsonMediaType, jsonBody)

        val request = Request.Builder()
            .url("$BASE_URL/api/usuarios/registrar")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@LoginActivity, "🌱 ¡Usuario '$user' registrado con éxito!", Toast.LENGTH_LONG).show()
                    } else {
                        val errorMsg = responseData?.let {
                            try { JSONObject(it).optString("detail") } catch(_: Exception) { "Error en backend" }
                        } ?: "Error de datos"
                        Toast.makeText(this@LoginActivity, "⚠️ No se pudo registrar: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@LoginActivity, "Error de red al intentar registrar", Toast.LENGTH_LONG).show()
            }
        }
    }
}