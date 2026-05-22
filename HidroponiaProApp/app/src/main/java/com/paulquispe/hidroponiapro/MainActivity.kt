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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.*
import java.util.concurrent.TimeUnit

@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseWebSocketUrl = "ws://127.0.0.1:8000/ws/invernadero"
    private var idCultivo: String = "lechuga"
    private var tokenJwt: String = ""
    private var mapaCultivos: Map<String, String> = emptyMap()

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
    private val handlerTransmision = Handler(Looper.getMainLooper())
    private val handlerInerciaFisica = Handler(Looper.getMainLooper())

    private val intervaloTransmision = 3000L
    private val intervaloFisica = 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
        tokenJwt = sharedPref.getString("JWT_TOKEN", "") ?: ""

        inicializarVistas()
        bloquearSeekBars()
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

    private fun bloquearSeekBars() {
        seekTemperatura.isEnabled = false; seekHumedad.isEnabled = false
        seekPH.isEnabled = false; seekLuz.isEnabled = false
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
        // AQUÍ ES DONDE ESTABA EL ERROR: Cambiar Button por ImageButton
        findViewById<ImageButton>(R.id.btnRegistrarPlanta).setOnClickListener {
            val intent = Intent(this, RegistrarCultivoActivity::class.java)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.btnCerrarSesion).setOnClickListener {
            val sharedPref = getSharedPreferences("AUTH_PREFS", Context.MODE_PRIVATE)
            sharedPref.edit().remove("JWT_TOKEN").apply()

            webSocket?.close(1000, "Cierre voluntario")
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun cargarCultivosDesdeRetrofit() {
        lifecycleScope.launch {
            try {
                val api = RetrofitClient.getApiService(tokenJwt)
                val res = withContext(Dispatchers.IO) { api.listarCultivos() }
                if (res.isSuccessful && res.body() != null) {
                    // Creamos un mapa: Nombre (UI) -> ID (BD)
                    mapaCultivos = res.body()!!.cultivos.associate { it.nombre_verdura to it.id_cultivo }
                    configurarSpinner(res.body()!!.cultivos.map { it.nombre_verdura })
                }
            } catch (e: Exception) { txtEstadoConexion.text = "Error cargando lista" }
        }
    }

    private fun configurarSpinner(nombres: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nombres)
        spinnerCultivos.adapter = adapter
        spinnerCultivos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                val nombreSeleccionado = nombres[pos]
                // Obtenemos el ID real desde el mapa
                idCultivo = mapaCultivos[nombreSeleccionado] ?: nombreSeleccionado.lowercase()

                txtNombreVerdura.text = "🌱 Conectando a $nombreSeleccionado (ID: $idCultivo)..."

                handlerTransmision.removeCallbacksAndMessages(null)
                webSocket?.close(1000, "Cambio")
                conectarAlWebSocketIntegrado()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun conectarAlWebSocketIntegrado() {
        val urlCompleta = "$baseWebSocketUrl/$idCultivo?token=$tokenJwt"
        val request = Request.Builder().url(urlCompleta).build()

        // Usamos el Listener directo para tener control total del evento 'onOpen'
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { txtEstadoConexion.text = "✅ Conectado" }
                // IMPORTANTE: Enviar datos justo al abrir para mantener la sesión activa
                enviarDatosInstantaneos()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                procesarComandoOJsonScada(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { txtEstadoConexion.text = "❌ Error: ${t.message}" }
            }
        })
    }

    private fun procesarComandoOJsonScada(texto: String) {
        runOnUiThread {
            try {
                if (texto.length == 12 && !texto.contains("{")) {
                    val c = texto.chunked(3)
                    estadoActTemp = cuandoBit(c[0]); estadoActHum = cuandoBit(c[1])
                    estadoActPh = cuandoBit(c[2]); estadoActLuz = cuandoBit(c[3])
                } else {
                    val j = JSONObject(texto)
                    txtNombreVerdura.text = "🌱 ${j.optString("txt_verdura", "Cultivo Activo")}"
                    txtRangosOptimos.text = j.optString("txt_rangos", "Sincronizado")
                    estadoActTemp = j.optString("actuador_temp", "VERDE")
                    estadoActHum = j.optString("actuador_hum", "VERDE")
                    estadoActPh = j.optString("actuador_ph", "VERDE")
                    estadoActLuz = j.optString("actuador_luz", "VERDE")
                }
                actualizarLedSCADA(ledTemperatura, txtActuadorTemperatura, "Extractor", estadoActTemp)
                actualizarLedSCADA(ledHumedad, txtActuadorHumedad, "Bomba", estadoActHum)
                actualizarLedSCADA(ledPH, txtActuadorPH, "pH", estadoActPh)
                actualizarLedSCADA(ledLuz, txtActuadorLuz, "Luz", estadoActLuz)
            } catch (e: Exception) { txtEstadoConexion.text = "Error de sincronización" }
        }
    }

    private fun cuandoBit(b: String) = when(b) { "010" -> "VERDE"; "100" -> "ROJO"; "001" -> "AZUL"; else -> "VERDE" }

    private fun actualizarLedSCADA(v: View, t: TextView, n: String, s: String) {
        val c = if (s == "ROJO") "#EF4444" else if (s == "AZUL") "#3B82F6" else "#10B981"
        v.backgroundTintList = ColorStateList.valueOf(Color.parseColor(c))
        t.text = "$n: ${if (s == "VERDE") "APAGADO" else "ACTIVO"} ($s)"
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

    // ... (resto de tu código arriba)

    private fun iniciarLoopFisicaEntorno() {
        handlerInerciaFisica.post(object : Runnable {
            override fun run() {
                if (estadoActTemp == "ROJO") seekTemperatura.progress -= 1
                else if (estadoActTemp == "AZUL") seekTemperatura.progress += 1
                actualizarDisplaysGraficos()
                handlerInerciaFisica.postDelayed(this, intervaloFisica)
            }
        })
    }

    // ÚNICA DECLARACIÓN DE LA FUNCIÓN
    private fun enviarDatosInstantaneos() {
        try {
            val j = JSONObject().apply {
                put("temp", seekTemperatura.progress)
                put("hum", seekHumedad.progress)
                put("ph", seekPH.progress)
                put("luz", seekLuz.progress)
                put("id_cultivo", idCultivo)
            }
            if (webSocket == null) return
            webSocket?.send(    j.toString())
        } catch (e: Exception) {
            android.util.Log.e("WEBSOCKET_SEND", "Error enviando datos: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Cierre")
        handlerTransmision.removeCallbacksAndMessages(null)
        handlerInerciaFisica.removeCallbacksAndMessages(null)
    }
}