package com.paulquispe.hidroponiapro

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.*
import java.util.concurrent.TimeUnit

@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    // IMPORTANTE: Cambia "127.0.0.1" por la IP local de tu PC si pruebas en un celular real
    private val baseWebSocketUrl = "ws://192.168.2.108:8000/ws/invernadero"

    private var idCultivo: String = "lechuga"
    private var tokenJwt: String = ""
    private var mapaCultivos: Map<String, String> = emptyMap()

    // Variable para controlar que la inercia solo inicie tras recibir datos
    private var datosRecibidos = false

    private lateinit var txtNombreVerdura: TextView
    private lateinit var txtRangosOptimos: TextView
    private lateinit var txtLabelTemperatura: TextView
    private lateinit var txtLabelHumedad: TextView
    private lateinit var txtLabelPH: TextView
    private lateinit var txtLabelLuz: TextView
    private lateinit var txtEstadoConexion: TextView
    private lateinit var spinnerCultivos: Spinner

    private lateinit var ledTemperatura: View
    private lateinit var ledHumedad: View
    private lateinit var ledPH: View
    private lateinit var ledLuz: View

    private lateinit var txtActuadorTemperatura: TextView
    private lateinit var txtActuadorHumedad: TextView
    private lateinit var txtActuadorPH: TextView
    private lateinit var txtActuadorLuz: TextView

    private lateinit var seekTemperatura: SeekBar
    private lateinit var seekHumedad: SeekBar
    private lateinit var seekPH: SeekBar
    private lateinit var seekLuz: SeekBar

    private var estadoActTemp = "VERDE"
    private var estadoActHum = "VERDE"
    private var estadoActPh = "VERDE"
    private var estadoActLuz = "VERDE"

    private var webSocket: WebSocket? = null
    private val handlerInerciaFisica = Handler(Looper.getMainLooper())
    private val intervaloFisica = 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
        tokenJwt = sharedPref.getString("JWT_TOKEN", "") ?: ""

        inicializarVistas()
        // NOTA: No llamamos a bloquearSeekBars() para permitir interacción
        configurarBotonesPerturbacion()
        configurarBotonesNavegacion()

        cargarCultivosDesdeRetrofit()
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
        spinnerCultivos = findViewById(R.id.spinnerCultivos)

        ledTemperatura = findViewById(R.id.ledTemperatura); ledHumedad = findViewById(R.id.ledHumedad)
        ledPH = findViewById(R.id.ledPH); ledLuz = findViewById(R.id.ledLuz)

        txtActuadorTemperatura = findViewById(R.id.txtActuadorTemperatura); txtActuadorHumedad = findViewById(R.id.txtActuadorHumedad)
        txtActuadorPH = findViewById(R.id.txtActuadorPH); txtActuadorLuz = findViewById(R.id.txtActuadorLuz)

        seekTemperatura = findViewById(R.id.seekTemperatura); seekHumedad = findViewById(R.id.seekHumedad)
        seekPH = findViewById(R.id.seekPH); seekLuz = findViewById(R.id.seekLuz)
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
    private val registrarLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Si el registro fue exitoso (RESULT_OK), refrescamos la lista
        if (result.resultCode == RESULT_OK) {
            cargarCultivosDesdeRetrofit()
        }
    }

    private fun configurarBotonesNavegacion() {
        // 1. Usamos el 'registrarLauncher' que definimos antes
        findViewById<ImageButton>(R.id.btnRegistrarPlanta).setOnClickListener {
            val intent = Intent(this, RegistrarCultivoActivity::class.java)
            registrarLauncher.launch(intent)
        }

        // 2. Mantienes tu lógica de cierre de sesión
        findViewById<ImageButton>(R.id.btnCerrarSesion).setOnClickListener {
            getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE).edit().remove("JWT_TOKEN").apply()
            webSocket?.close(1000, "Cierre")
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent); finish()
        }
    }

    private fun cargarCultivosDesdeRetrofit() {
        lifecycleScope.launch {
            try {
                val api = RetrofitClient.getApiService(tokenJwt)
                val res = withContext(Dispatchers.IO) { api.listarCultivos() }
                if (res.isSuccessful && res.body() != null) {
                    mapaCultivos = res.body()!!.cultivos.associate { it.nombre_verdura to it.id_cultivo }
                    val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, res.body()!!.cultivos.map { it.nombre_verdura })
                    spinnerCultivos.adapter = adapter
                    spinnerCultivos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                            val nuevoId = mapaCultivos[spinnerCultivos.getItemAtPosition(pos).toString()] ?: "lechuga"

                            if (idCultivo != nuevoId) { // Solo actuar si realmente cambió
                                idCultivo = nuevoId
                                datosRecibidos = false
                                webSocket?.close(1000, "Cambio")
                                conectarAlWebSocketIntegrado()

                                // --- AQUÍ ESTÁ LA CLAVE ---
                                // Enviamos un "ping" o estado nulo para que el servidor responda inmediatamente
                                // con los nuevos rangos y estados para el nuevo cultivo.
                                Handler(Looper.getMainLooper()).postDelayed({
                                    enviarDatosInstantaneos()
                                }, 500) // Espera 500ms a que conecte el WS
                            }
                        }
                        override fun onNothingSelected(p0: AdapterView<*>?) {}
                    }
                }
            } catch (e: Exception) { txtEstadoConexion.text = "Error cargando lista" }
        }
    }

    private fun conectarAlWebSocketIntegrado() {
        webSocket = client.newWebSocket(Request.Builder().url("$baseWebSocketUrl/$idCultivo?token=$tokenJwt").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) { runOnUiThread { txtEstadoConexion.text = "✅ Conectado" } }
            override fun onMessage(ws: WebSocket, text: String) { procesarComandoOJsonScada(text) }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { runOnUiThread { txtEstadoConexion.text = "❌ Error" } }
        })
    }

    private fun procesarComandoOJsonScada(texto: String) {
        runOnUiThread {
            try {
                val j = JSONObject(texto)
                txtNombreVerdura.text = "🌱 ${j.optString("txt_verdura")}"
                txtRangosOptimos.text = j.optString("txt_rangos")
                estadoActTemp = j.optString("actuador_temp", "VERDE")
                estadoActHum = j.optString("actuador_hum", "VERDE")
                estadoActPh = j.optString("actuador_ph", "VERDE")
                estadoActLuz = j.optString("actuador_luz", "VERDE")

                actualizarLedSCADA(ledTemperatura, txtActuadorTemperatura, "Temp", estadoActTemp)
                actualizarLedSCADA(ledHumedad, txtActuadorHumedad, "Hum", estadoActHum)
                actualizarLedSCADA(ledPH, txtActuadorPH, "pH", estadoActPh)
                actualizarLedSCADA(ledLuz, txtActuadorLuz, "Luz", estadoActLuz)

                datosRecibidos = true // Ahora permitimos que la inercia funcione
            } catch (e: Exception) {}
        }
    }

    private fun actualizarLedSCADA(v: View, t: TextView, n: String, s: String) {
        val c = if (s == "ROJO") "#EF4444" else if (s == "AZUL") "#3B82F6" else "#10B981"
        v.backgroundTintList = ColorStateList.valueOf(Color.parseColor(c))
        t.text = "$n: ${if (s == "VERDE") "OFF" else "ON"} ($s)"
    }

    private fun iniciarLoopFisicaEntorno() {
        handlerInerciaFisica.post(object : Runnable {
            override fun run() {
                if (datosRecibidos) {
                    var huboCambio = false

                    // Lógica inteligente: solo ajusta si el estado es distinto a VERDE
                    fun calcularAjuste(valor: Int, estado: String, factor: Int): Int {
                        if (estado == "VERDE") return valor
                        val direccion = if (estado == "ROJO") -1 else 1
                        return (valor + (direccion * factor)).coerceIn(0, 1000)
                    }

                    // Aplicamos los ajustes (Manteniendo tus factores de sensibilidad)
                    val nuevaTemp = calcularAjuste(seekTemperatura.progress, estadoActTemp, 1)
                    val nuevaHum = calcularAjuste(seekHumedad.progress, estadoActHum, 1)
                    val nuevoPH = calcularAjuste(seekPH.progress, estadoActPh, 1)
                    val nuevaLuz = calcularAjuste(seekLuz.progress, estadoActLuz, 10)

                    // Verificamos si hubo cambios para evitar enviar basura al servidor
                    if (nuevaTemp != seekTemperatura.progress || nuevaHum != seekHumedad.progress ||
                        nuevoPH != seekPH.progress || nuevaLuz != seekLuz.progress) {

                        seekTemperatura.progress = nuevaTemp
                        seekHumedad.progress = nuevaHum
                        seekPH.progress = nuevoPH
                        seekLuz.progress = nuevaLuz

                        huboCambio = true
                    }

                    if (huboCambio) {
                        actualizarDisplaysGraficos()
                        enviarDatosInstantaneos()
                    }
                }
                handlerInerciaFisica.postDelayed(this, 500L)
            }
        })
    }

    private fun perturbarSensor(s: SeekBar, d: Int, i: Boolean) {
        s.progress = (if (i) s.progress + d else s.progress - d).coerceIn(0, s.max)
        actualizarDisplaysGraficos(); enviarDatosInstantaneos()
    }

    private fun actualizarDisplaysGraficos() {
        txtLabelTemperatura.text = "Temperatura: ${seekTemperatura.progress}.0 °C"
        txtLabelHumedad.text = "Humedad Ambiental: ${seekHumedad.progress} %"
        txtLabelPH.text = "Nivel de pH: ${seekPH.progress / 10.0f}"
        txtLabelLuz.text = "Luminosidad: ${seekLuz.progress} Lux"
    }

    private fun enviarDatosInstantaneos() {
        try {
            webSocket?.send(JSONObject().apply {
                put("temp", seekTemperatura.progress); put("hum", seekHumedad.progress)
                put("ph", seekPH.progress / 10.0f); put("luz", seekLuz.progress); put("id_cultivo", idCultivo)
            }.toString())
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Cierre")
        handlerInerciaFisica.removeCallbacksAndMessages(null)
    }
}