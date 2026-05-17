package com.paulquispe.hidroponiapro

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import okhttp3.*
import java.util.concurrent.TimeUnit

@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity() {

    // Si usas dispositivo físico con 'adb reverse tcp:8000 tcp:8000' mantén 127.0.0.1
    // Si usas el emulador nativo de Android Studio, cambia temporalmente a "10.0.2.2"
    private val urlWebSocket = "ws://127.0.0.1:8000/ws/invernadero"

    // Componentes de la Interfaz Gráfica
    private lateinit var txtNombreVerdura: TextView
    private lateinit var txtRangosOptimos: TextView
    private lateinit var txtLabelTemperatura: TextView
    private lateinit var txtLabelHumedad: TextView
    private lateinit var txtLabelPH: TextView
    private lateinit var txtLabelLuz: TextView
    private lateinit var txtEstadoConexion: TextView

    private lateinit var seekTemperatura: SeekBar
    private lateinit var seekHumedad: SeekBar
    private lateinit var seekPH: SeekBar
    private lateinit var seekLuz: SeekBar

    // Clientes de Infraestructura de Red
    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null

    // Manejador de hilos para el ciclo repetitivo de transmisión
    private val handler = Handler(Looper.getMainLooper())
    private val intervaloTransmision = 3000L // 3 segundos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inicializarVistas()
        configurarListenersSeekBars()

        // Inicializar el cliente HTTP optimizado para soportar WebSockets asíncronos
        client = OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        // Iniciar la negociación del apretón de manos (Handshake) con FastAPI
        conectarAlServidorEspejo()
    }

    private fun inicializarVistas() {
        txtNombreVerdura = findViewById(R.id.txtNombreVerdura)
        txtRangosOptimos = findViewById(R.id.txtRangosOptimos)
        txtLabelTemperatura = findViewById(R.id.txtLabelTemperatura)
        txtLabelHumedad = findViewById(R.id.txtLabelHumedad)
        txtLabelPH = findViewById(R.id.txtLabelPH)
        txtLabelLuz = findViewById(R.id.txtLabelLuz)
        txtEstadoConexion = findViewById(R.id.txtEstadoConexion)

        seekTemperatura = findViewById(R.id.seekTemperatura)
        seekHumedad = findViewById(R.id.seekHumedad)
        seekPH = findViewById(R.id.seekPH)
        seekLuz = findViewById(R.id.seekLuz)
    }

    private fun configurarListenersSeekBars() {
        seekTemperatura.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtLabelTemperatura.text = "Temperatura: ${progress}.0 °C"
                if (fromUser) enviarDatosInstantaneos()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekHumedad.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtLabelHumedad.text = "Humedad Ambiental: $progress %"
                if (fromUser) enviarDatosInstantaneos()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekPH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // CORREGIDO: Convertimos la escala entera (0-140) a decimal real (0.0 - 14.0)
                val phReal = progress / 10.0f
                txtLabelPH.text = "Nivel de pH: $phReal"
                if (fromUser) enviarDatosInstantaneos()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekLuz.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtLabelLuz.text = "Luminosidad: $progress Lux"
                if (fromUser) enviarDatosInstantaneos()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Establece el puente de comunicación persistente mediante WebSockets
     */
    private fun conectarAlServidorEspejo() {
        val request = Request.Builder().url(urlWebSocket).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    txtNombreVerdura.text = "🌱 Monitoreo Activo: Configuración Remota"
                    txtRangosOptimos.text = "Clúster Cassandra Conectado vía FastAPI"
                    txtEstadoConexion.text = "✅ Canal WebSocket Abierto"
                }
                iniciarLoopTransmision()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val respuestaJson = JSONObject(text)
                        val actTemp = respuestaJson.getString("actuador_temp")
                        val actHum = respuestaJson.getString("actuador_hum")
                        val actPh = respuestaJson.getString("actuador_ph")
                        val actLuz = respuestaJson.getString("actuador_luz")

                        txtEstadoConexion.text = "📥 Respuesta Actuadores Cassandra:\n" +
                                "TEMP: $actTemp | HUM: $actHum | pH: $actPh | LUZ: $actLuz"
                    } catch (e: Exception) {
                        txtEstadoConexion.text = "❌ Error al decodificar respuesta del clúster: ${e.localizedMessage}"
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { txtEstadoConexion.text = "⚠️ Cerrando canal WebSocket..." }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    val detalleError = t.localizedMessage ?: "Error de Handshake / Timeout de red"
                    txtEstadoConexion.text = "❌ Falla de Red: $detalleError\nURL: $urlWebSocket"
                }
            }
        })
    }

    /**
     * Ciclo automatizado que inyecta datos puros de sensores al túnel WebSocket abierto
     */
    private fun iniciarLoopTransmision() {
        // Removemos callbacks previos para evitar duplicación del loop si hay reconexiones
        handler.removeCallbacksAndMessages(null)

        handler.post(object : Runnable {
            override fun run() {
                enviarDatosInstantaneos()
                handler.postDelayed(this, intervaloTransmision)
            }
        })
    }

    /**
     * Inyecta de inmediato un JSON con el estado de las barras al flujo de red.
     */
    private fun enviarDatosInstantaneos() {
        // Evitamos calcular si el objeto o la conexión no se han inicializado
        if (webSocket == null) return

        try {
            val jsonPayload = JSONObject().apply {
                put("temp", seekTemperatura.progress.toDouble())
                put("hum", seekHumedad.progress.toDouble())
                // CORREGIDO: Escalamos el pH antes de despacharlo a Cassandra
                put("ph", (seekPH.progress.toDouble() / 10.0))
                put("luz", seekLuz.progress.toDouble())
            }
            webSocket?.send(jsonPayload.toString())
        } catch (e: Exception) {
            runOnUiThread {
                txtEstadoConexion.text = "⚠️ Error de envío instantáneo: ${e.localizedMessage}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Activity destruida de manera limpia")
        handler.removeCallbacksAndMessages(null)
    }
}