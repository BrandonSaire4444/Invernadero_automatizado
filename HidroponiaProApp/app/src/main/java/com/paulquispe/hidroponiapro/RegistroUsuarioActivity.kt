package com.paulquispe.hidroponiapro

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RegistroUsuarioActivity : AppCompatActivity() {

    private lateinit var txtNombre: EditText
    private lateinit var txtUsername: EditText
    private lateinit var txtPassword: EditText
    private lateinit var btnRegistrar: Button

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
                Toast.makeText(this, "⚠️ Por favor, llene todos los campos", Toast.LENGTH_SHORT).show()
            } else if (password.length < 6) {
                txtPassword.error = "La contraseña debe tener al menos 6 caracteres"
            } else {
                enviarRegistroAlBackend(nombre, username, password)
            }
        }
    }

    private fun enviarRegistroAlBackend(nombre: String, username: String, password: String) {
        val nuevoOperador = UsuarioRegistro(nombre, username, password)

        lifecycleScope.launch {
            try {
                btnRegistrar.isEnabled = false

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApiService(null).registrarUsuario(nuevoOperador)
                }

                if (response.isSuccessful) {
                    Toast.makeText(this@RegistroUsuarioActivity, "¡Usuario registrado en Cassandra!", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val respuestaString = response.errorBody()?.string()
                    try {
                        val jsonError = JSONObject(respuestaString ?: "{}")
                        val detalle = jsonError.optString("detail", "Error en el servidor")
                        Toast.makeText(this@RegistroUsuarioActivity, "⚠️ $detalle", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        // AQUÍ ESTÁ EL AJUSTE: Usamos la función .code() para máxima compatibilidad
                        Toast.makeText(this@RegistroUsuarioActivity, "Error: Código ${response.code()}", Toast.LENGTH_LONG).show()
                    }
                    btnRegistrar.isEnabled = true
                }
            } catch (e: Exception) {
                Toast.makeText(this@RegistroUsuarioActivity, "❌ Error de conexión con el servidor", Toast.LENGTH_LONG).show()
                btnRegistrar.isEnabled = true
            }
        }
    }
}