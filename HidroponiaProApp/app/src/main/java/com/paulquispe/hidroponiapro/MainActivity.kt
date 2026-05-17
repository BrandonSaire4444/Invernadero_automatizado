package com.paulquispe.hidroponiapro

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
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

    // Componentes del Panel SCADA
    private lateinit var ledTemperatura: View
    private lateinit var ledHumedad: View
    private lateinit var ledPH: View
    private lateinit var ledLuz: View

    private lateinit var txtActuadorTemperatura: TextView
    private lateinit var txtActuadorHumedad: TextView
    private lateinit var txtActuadorPH: TextView
    private lateinit var txtActuadorLuz: TextView

    // Las SeekBars ahora actúan como pantallas analógicas (lectura pura)
    private lateinit var seekTemperatura: SeekBar
    private lateinit var seekHumedad: SeekBar
    private lateinit var seekPH: SeekBar
    private lateinit var seekLuz: SeekBar

    // NUEVO MOTOR DE ESTADOS: Almacenan la directiva del backend para simular la inercia
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

    private val intervaloTransmision = 3000L // Transmisión regular a Cassandra cada 3s
    private val intervaloFisica = 1000L       // Reacción de actuadores cada 1s (Lento y visible)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inicializarVistas()
        bloquearSeekBars()         // Volvemos las barras de lectura pura
        configurarBotonesPerturbacion() // Los botones inyectan anomalías del entorno

        client = OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        conectarAlServidorEspejo()
        iniciarLoopFisicaEntorno() // Arranca el motor de lazo cerrado
    }

    private fun inicializarVistas() {
        txtNombreVerdura = findViewById(R.id.txtNombreVerdura)
        txtRangosOptimos = findViewById(R.id.txtRangosOptimos)
        txtLabelTemperatura = findViewById(R.id.txtLabelTemperatura)
        txtLabelHumedad = findViewById(R.id.txtLabelHumedad)
        txtLabelPH = findViewById(R.id.txtLabelPH)
        txtLabelLuz = findViewById(R.id.txtLabelLuz)
        txtEstadoConexion = findViewById(R.id.txtEstadoConexion)

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

    /**
     * BUENA PRÁCTICA: El usuario ya no puede arrastrar las barras con el dedo.
     * Ahora se comportan como displays industriales analógicos.
     */
    private fun bloquearSeekBars() {
        seekTemperatura.setEnabled(false)
        seekHumedad.setEnabled(false)
        seekPH.setEnabled(false)
        seekLuz.setEnabled(false)
    }

    /**
     * EXCELENCIA OPERACIONAL: Los botones simulan perturbaciones externas
     * provocadas en el ecosistema hidropónico.
     */
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

    private fun perturbarSensor(seekBar: SeekBar, delta: Int, incrementar: Boolean) {
        val actual = seekBar.progress
        val nuevo = if (incrementar) actual + delta else actual - delta
        seekBar.progress = nuevo.coerceIn(0, seekBar.max)
        actualizarDisplaysGraficos()
        enviarDatosInstantaneos() // Notifica de inmediato al backend el cambio brusco
    }

    private fun actualizarDisplaysGraficos() {
        txtLabelTemperatura.text = "Temperatura: ${seekTemperatura.progress}.0 °C"
        txtLabelHumedad.text = "Humedad Ambiental: ${seekHumedad.progress} %"
        txtLabelPH.text = "Nivel de pH: ${seekPH.progress / 10.0f}"
        txtLabelLuz.text = "Luminosidad: ${seekLuz.progress} Lux"
    }

    /**
     * MOTOR DE INERCIA DE HARDWARE (LAZO CERRADO):
     * Simula el retardo físico real. Si el backend ordena mitigar (ROJO o AZUL),
     * este bucle altera los valores de las barras segundo a segundo hacia el equilibrio.
     */
    private fun iniciarLoopFisicaEntorno() {
        handlerInerciaFisica.post(object : Runnable {
            override fun run() {
                var cambiosDetectados = false

                // 1. Simulación Física de Termorregulación (Extractor / Calentador)
                if (estadoActTemp == "ROJO" && seekTemperatura.progress > 0) {
                    seekTemperatura.progress -= 1 // Extractor enfriando paulatinamente
                    cambiosDetectados = true
                } else if (estadoActTemp == "AZUL" && seekTemperatura.progress < seekTemperatura.max) {
                    seekTemperatura.progress += 1 // Calentador elevando la temperatura
                    cambiosDetectados = true
                }

                // 2. Simulación Física de Humedad (Nebulizador / Extractores)
                if (estadoActHum == "ROJO" && seekHumedad.progress > 0) {
                    seekHumedad.progress -= 1 // Flujo de aire secando el ambiente
                    cambiosDetectados = true
                } else if (estadoActHum == "AZUL" && seekHumedad.progress < seekHumedad.max) {
                    seekHumedad.progress += 1 // Nebulización inyectando microgotas
                    cambiosDetectados = true
                }

                // 3. Simulación de Reacción Química en Solución Nutritiva (pH)
                if (estadoActPh == "ROJO" && seekPH.progress > 0) {
                    seekPH.progress -= 1 // Dosificador añadiendo ácido nítrico/fosfórico lentamente
                    cambiosDetectados = true
                } else if (estadoActPh == "AZUL" && seekPH.progress < seekPH.max) {
                    seekPH.progress += 1 // Dosificador añadiendo solución básica
                    cambiosDetectados = true
                }

                // 4. Simulación Física de Luminosidad (Foto-periodo)
                if (estadoActLuz == "ROJO" && seekLuz.progress > 0) {
                    seekLuz.progress -= 5 // Despliegue motorizado de mallas de sombreo
                    cambiosDetectados = true
                } else if (estadoActLuz == "AZUL" && seekLuz.progress < seekLuz.max) {
                    seekLuz.progress += 5 // Encendido gradual de paneles LED de espectro completo
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
        val request = Request.Builder().url(urlWebSocket).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    txtEstadoConexion.text = "✅ Canal WebSocket Abierto"
                }
                iniciarLoopTransmision()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val respuestaJson = JSONObject(text)

                        // Sincronización del Diccionario de Datos del cultivo desde Cassandra
                        if (respuestaJson.has("txt_verdura")) {
                            txtNombreVerdura.text = respuestaJson.getString("txt_verdura")
                        }
                        if (respuestaJson.has("txt_rangos")) {
                            txtRangosOptimos.text = respuestaJson.getString("txt_rangos")
                        }

                        // Sincronización de variables de estado global para el motor de inercia
                        estadoActTemp = respuestaJson.getString("actuador_temp")
                        estadoActHum = respuestaJson.getString("actuador_hum")
                        estadoActPh = respuestaJson.getString("actuador_ph")
                        estadoActLuz = respuestaJson.getString("actuador_luz")

                        // Pintado en caliente de la Interfaz SCADA
                        actualizarLedSCADA(ledTemperatura, txtActuadorTemperatura, "Extractor / Calefactor", estadoActTemp)
                        actualizarLedSCADA(ledHumedad, txtActuadorHumedad, "Bomba / Nebulizador", estadoActHum)
                        actualizarLedSCADA(ledPH, txtActuadorPH, "Dosificador de Solución", estadoActPh)
                        actualizarLedSCADA(ledLuz, txtActuadorLuz, "Iluminación Artificial", estadoActLuz)

                        txtEstadoConexion.text = "📥 Tablero SCADA Sincronizado en Tiempo Real"
                    } catch (e: Exception) {
                        txtEstadoConexion.text = "❌ Error de sincronización: ${e.localizedMessage}"
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { txtEstadoConexion.text = "⚠️ Cerrando canal WebSocket..." }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    val detalleError = t.localizedMessage ?: "Timeout / Caída de red"
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
                colorHex = "#10B981" // Verde Esmeralda
                mensajeEstado = "APAGADO / RANGO ÓPTIMO"
            }
            "ROJO" -> {
                colorHex = "#EF4444" // Rojo Intenso
                mensajeEstado = "ACTIVO (ESTABILIZANDO EXCESO ↓)"
            }
            "AZUL" -> {
                colorHex = "#3B82F6" // Azul Eléctrico
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