package com.paulquispe.hidroponiapro
import android.widget.ImageButton
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity() {

    // --- VARIABLES DE INFRAESTRUCTURA DINÁMICA ---
    private val baseWebSocketUrl = "ws://192.168.2.108:8000/ws/invernadero"
    private val baseHttpUrl = "http://192.168.2.108:8000/api/cultivos"
    private var idCultivo: String = "lechuga_01"
    private var tokenJwt: String = ""

    // Componentes de la Interfaz Gráfica
    private lateinit var txtNombreVerdura: TextView
    private lateinit var txtRangosOptimos: TextView
    private lateinit var txtLabelTemperatura: TextView
    private lateinit var txtLabelHumedad: TextView
    private lateinit var txtLabelPH: TextView
    private lateinit var txtLabelLuz: TextView
    private lateinit var txtEstadoConexion: TextView
    private lateinit var spinnerCultivos: Spinner

    // Componentes del Panel SCADA
    private lateinit var ledTemperatura: View
    private lateinit var ledHumedad: View
    private lateinit var ledPH: View
    private lateinit var ledLuz: View

    private lateinit var txtActuadorTemperatura: TextView
    private lateinit var txtActuadorHumedad: TextView
    private lateinit var txtActuadorPH: TextView
    private lateinit var txtActuadorLuz: TextView

    // Las SeekBars actúan como pantallas analógicas (lectura pura)
    private lateinit var seekTemperatura: SeekBar
    private lateinit var seekHumedad: SeekBar
    private lateinit var seekPH: SeekBar
    private lateinit var seekLuz: SeekBar

    // MOTOR DE ESTADOS: Directivas del backend
    private var estadoActTemp = "VERDE"
    private var estadoActHum = "VERDE"
    private var estadoActPh = "VERDE"
    private var estadoActLuz = "VERDE"

    // Clientes de Infraestructura de Red
    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null

    // Manejadores de hilos independientes (Evitan congelamiento de UI)
    private val handlerTransmision = Handler(Looper.getMainLooper())
    private val handlerInerciaFisica = Handler(Looper.getMainLooper())

    private val intervaloTransmision = 3000L // Transmisión a Cassandra cada 3s
    private val intervaloFisica = 1000L       // Reacción de actuadores cada 1s

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
        tokenJwt = sharedPref.getString("JWT_TOKEN", "") ?: ""

        client = OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        inicializarVistas()
        bloquearSeekBars()
        configurarBotonesPerturbacion()
        configurarBotonesNavegacion() // <-- Este método se encargará de todo

        cargarCultivosDesdeApi()
        iniciarLoopFisicaEntorno()
    }

    private fun inicializarVistas() {
        txtNombreVerdura = findViewById(R.id.txtNombreVerdura)
        txtRangosOptimos = findViewById(R.id.txtRangosOptimos)
        txtLabelTemperatura = findViewById(R.id.txtLabelTemperatura)
        txtLabelHumedad = findViewById(R.id.txtLabelHumedad)
        txtLabelPH = findViewById(R.id.txtLabelPH)
        txtLabelLuz = findViewById(R.id.txtLabelLuz)
        txtEstadoConexion = findViewById(R.id.txtEstadoConexion)
        spinnerCultivos = findViewById(R.id.spinnerCultivos) // Asegúrate de tener este ID en tu XML

        ledTemperatura = findViewById(R.id.ledTemperatura)
        ledHumedad = findViewById(R.id.ledHumedad)
        ledPH = findViewById(R.id.ledPH)
        ledLuz = findViewById(R.id.ledLuz)

        txtActuadorTemperatura = findViewById(R.id.txtActuadorTemperatura)
        txtActuadorHumedad = findViewById(R.id.txtActuadorHumedad)
        txtActuadorPH = findViewById(R.id.txtActuadorPH)
        txtActuadorLuz = findViewById(R.id.txtActuadorLuz)

        seekTemperatura = findViewById(R.id.seekTemperatura)
        seekHumedad = findViewById(R.id.seekHumedad)
        seekPH = findViewById(R.id.seekPH)
        seekLuz = findViewById(R.id.seekLuz)
    }

    private fun bloquearSeekBars() {
        seekTemperatura.setEnabled(false)
        seekHumedad.setEnabled(false)
        seekPH.setEnabled(false)
        seekLuz.setEnabled(false)
    }

    private fun configurarBotonesPerturbacion() {
        findViewById<Button>(R.id.btnTempMas).setOnClickListener { perturbarSensor(seekTemperatura, 2, true) }
        findViewById<Button>(R.id.btnTempMenos).setOnClickListener { perturbarSensor(seekTemperatura, 2, false) }

        findViewById<Button>(R.id.btnHumMas).setOnClickListener { perturbarSensor(seekHumedad, 5, true) }
        findViewById<Button>(R.id.btnHumMenos).setOnClickListener { perturbarSensor(seekHumedad, 5, false) }

        findViewById<Button>(R.id.btnPhMas).setOnClickListener { perturbarSensor(seekPH, 3, true) }
        findViewById<Button>(R.id.btnPhMenos).setOnClickListener { perturbarSensor(seekPH, 3, false) }

        findViewById<Button>(R.id.btnLuzMas).setOnClickListener { perturbarSensor(seekLuz, 20, true) }
        findViewById<Button>(R.id.btnLuzMenos).setOnClickListener { perturbarSensor(seekLuz, 20, false) }
    }

    private fun configurarBotonesNavegacion() {
        // 🛡️ Enlace corregido a ImageButton para evitar el ClassCastException
        findViewById<ImageButton>(R.id.btnRegistrarPlanta).setOnClickListener {
            val intent = Intent(this, RegistrarCultivoActivity::class.java)
            startActivity(intent)
        }

        // 🛡️ Enlace corregido para el botón de salida segura
        findViewById<ImageButton>(R.id.btnCerrarSesion).setOnClickListener {
            val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
            sharedPref.edit().remove("JWT_TOKEN").apply()

            webSocket?.close(1000, "Cierre voluntario de sesión")
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun cargarCultivosDesdeApi() {
        val request = Request.Builder()
            .url(baseHttpUrl)
            .header("Authorization", "Bearer $tokenJwt")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    txtEstadoConexion.text = "⚠️ Error cargando matriz: Usando locales"
                    configurarSpinner(listOf("lechuga_01", "tomate_hidro", "fresa_premium"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        runOnUiThread { configurarSpinner(listOf("lechuga_01")) }
                        return
                    }
                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        val array = json.getJSONArray("cultivos")
                        val lista = ArrayList<String>()
                        for (i in 0 until array.length()) {
                            lista.add(array.getString(i))
                        }
                        runOnUiThread { configurarSpinner(lista) }
                    } catch (e: Exception) {
                        runOnUiThread { configurarSpinner(listOf("lechuga_01")) }
                    }
                }
            }
        })
    }

    private fun configurarSpinner(listaCultivos: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listaCultivos)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCultivos.adapter = adapter

        spinnerCultivos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val nuevoCultivo = listaCultivos[position]
                if (nuevoCultivo != idCultivo) {
                    idCultivo = nuevoCultivo

                    // Cancelamos y cerramos explícitamente el socket actual
                    handlerTransmision.removeCallbacksAndMessages(null)
                    webSocket?.close(1000, "Cambiando de monitoreo")
                    webSocket = null

                    conectarAlServidorEspejo()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    } // <--- 🛠️ ¡ESTA LLAVE FALTABA! Cierra correctamente configurarSpinner

    private fun perturbarSensor(seekBar: SeekBar, delta: Int, incrementar: Boolean) {
        val actual = seekBar.progress
        val nuevo = if (incrementar) actual + delta else actual - delta
        seekBar.progress = nuevo.coerceIn(0, seekBar.max)
        actualizarDisplaysGraficos()
        enviarDatosInstantaneos()
    }



    private fun actualizarDisplaysGraficos() {
        txtLabelTemperatura.text = "Temperatura: ${seekTemperatura.progress}.0 °C"
        txtLabelHumedad.text = "Humedad Ambiental: ${seekHumedad.progress} %"
        txtLabelPH.text = "Nivel de pH: ${seekPH.progress / 10.0f}"
        txtLabelLuz.text = "Luminosidad: ${seekLuz.progress} Lux"
    }

    private fun iniciarLoopFisicaEntorno() {
        handlerInerciaFisica.post(object : Runnable {
            override fun run() {
                var cambiosDetectados = false

                if (estadoActTemp == "ROJO" && seekTemperatura.progress > 0) {
                    seekTemperatura.progress -= 1
                    cambiosDetectados = true
                } else if (estadoActTemp == "AZUL" && seekTemperatura.progress < seekTemperatura.max) {
                    seekTemperatura.progress += 1
                    cambiosDetectados = true
                }

                if (estadoActHum == "ROJO" && seekHumedad.progress > 0) {
                    seekHumedad.progress -= 1
                    cambiosDetectados = true
                } else if (estadoActHum == "AZUL" && seekHumedad.progress < seekHumedad.max) {
                    seekHumedad.progress += 1
                    cambiosDetectados = true
                }

                if (estadoActPh == "ROJO" && seekPH.progress > 0) {
                    seekPH.progress -= 1
                    cambiosDetectados = true
                } else if (estadoActPh == "AZUL" && seekPH.progress < seekPH.max) {
                    seekPH.progress += 1
                    cambiosDetectados = true
                }

                if (estadoActLuz == "ROJO" && seekLuz.progress > 0) {
                    seekLuz.progress -= 5
                    cambiosDetectados = true
                } else if (estadoActLuz == "AZUL" && seekLuz.progress < seekLuz.max) {
                    seekLuz.progress += 5
                    cambiosDetectados = true
                }

                if (cambiosDetectados) {
                    actualizarDisplaysGraficos()
                }

                handlerInerciaFisica.postDelayed(this, intervaloFisica)
            }
        })
    }

    private fun conectarAlServidorEspejo() {
        val urlSincronizada = "$baseWebSocketUrl/$idCultivo?token=$tokenJwt"
        val request = Request.Builder().url(urlSincronizada).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    txtEstadoConexion.text = "✅ Canal SCADA [$idCultivo] Verificado"
                }
                iniciarLoopTransmision()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val respuestaJson = JSONObject(text)

                        // Usamos optString en lugar de getString para evitar excepciones si la clave varía o falta
                        val nombreVerdura = respuestaJson.optString("txt_verdura", "")
                        if (nombreVerdura.isNotEmpty()) {
                            txtNombreVerdura.text = nombreVerdura
                        }

                        // Validamos ambas variantes de claves ("txt_rangos" o "ranges") para soportar el backend
                        val rangos = when {
                            respuestaJson.has("txt_rangos") -> respuestaJson.getString("txt_rangos")
                            respuestaJson.has("ranges") -> respuestaJson.getString("ranges")
                            else -> ""
                        }
                        if (rangos.isNotEmpty()) {
                            txtRangosOptimos.text = rangos
                        }

                        // Lectura segura de actuadores
                        estadoActTemp = respuestaJson.optString("actuador_temp", "VERDE")
                        estadoActHum = respuestaJson.optString("actuador_hum", "VERDE")
                        estadoActPh = respuestaJson.optString("actuador_ph", "VERDE")
                        estadoActLuz = respuestaJson.optString("actuador_luz", "VERDE")

                        // Actualización del Tablero Gráfico
                        actualizarLedSCADA(ledTemperatura, txtActuadorTemperatura, "Extractor / Calefactor", estadoActTemp)
                        actualizarLedSCADA(ledHumedad, txtActuadorHumedad, "Bomba / Nebulizador", estadoActHum)
                        actualizarLedSCADA(ledPH, txtActuadorPH, "Dosificador de Solución", estadoActPh)
                        actualizarLedSCADA(ledLuz, txtActuadorLuz, "Iluminación Artificial", estadoActLuz)

                        txtEstadoConexion.text = "📥 Tablero SCADA Sincronizado en Tiempo Real"
                    } catch (e: Exception) {
                        txtEstadoConexion.text = "❌ Error de parseo SCADA: ${e.localizedMessage}"
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { txtEstadoConexion.text = "⚠️ Cerrando canal WebSocket..." }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    val detalleError = t.localizedMessage ?: "Rechazo de Credenciales / Servidor Caído"
                    txtEstadoConexion.text = "❌ Falla de Red: $detalleError"
                }
            }
        })
    }

    private fun actualizarLedSCADA(viewLed: View, textViewInfo: TextView, nombreDispositivo: String, estado: String) {
        val colorHex: String
        val mensajeEstado: String

        when (estado) {
            "VERDE" -> {
                colorHex = "#10B981"
                mensajeEstado = "APAGADO / RANGO ÓPTIMO"
            }
            "ROJO" -> {
                colorHex = "#EF4444"
                mensajeEstado = "ACTIVO (ESTABILIZANDO EXCESO ↓)"
            }
            "AZUL" -> {
                colorHex = "#3B82F6"
                mensajeEstado = "ACTIVO (ESTABILIZANDO DÉFICIT ↑)"
            }
            else -> {
                colorHex = "#64748B"
                mensajeEstado = "DESCONECTADO"
            }
        }

        viewLed.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colorHex))
        textViewInfo.text = "$nombreDispositivo: $mensajeEstado"
        textViewInfo.setTextColor(Color.parseColor(colorHex))
    }

    private fun iniciarLoopTransmision() {
        handlerTransmision.removeCallbacksAndMessages(null)
        handlerTransmision.post(object : Runnable {
            override fun run() {
                enviarDatosInstantaneos()
                handlerTransmision.postDelayed(this, intervaloTransmision)
            }
        })
    }

    private fun enviarDatosInstantaneos() {
        if (webSocket == null) return
        try {
            val jsonPayload = JSONObject().apply {
                put("temp", seekTemperatura.progress.toDouble())
                put("hum", seekHumedad.progress.toDouble())
                put("ph", (seekPH.progress.toDouble() / 10.0))
                put("luz", seekLuz.progress.toDouble())
            }
            webSocket?.send(jsonPayload.toString())
        } catch (e: Exception) {
            runOnUiThread {
                txtEstadoConexion.text = "⚠️ Error de envío: ${e.localizedMessage}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Cierre limpio de recursos")
        handlerTransmision.removeCallbacksAndMessages(null)
        handlerInerciaFisica.removeCallbacksAndMessages(null)
    }
}