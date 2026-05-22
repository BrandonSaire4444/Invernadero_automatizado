package com.paulquispe.hidroponiapro

import android.util.Log
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// Esta es una Clase que "hereda" (: WebSocketListener) las funciones de la librería OkHttp
class InvernaderoWebSocketListener(
    private val onCommandReceived: (String) -> Unit,
    private val onConnectionStatusChanged: (Boolean, String) -> Unit
) : WebSocketListener() {

    // Se ejecuta automáticamente cuando la conexión tiene éxito
    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d("SCADA_WS", "Conexión WebSocket establecida exitosamente.")
        onConnectionStatusChanged(true, "Conectado al servidor")
    }

    // Se ejecuta cada vez que el backend envía la cadena de 12 bits ("010010...")
    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d("SCADA_WS", "Comando de regulación recibido: $text")
        // Reenviamos el comando de control directamente a la actividad principal
        onCommandReceived(text)
    }

    // Se ejecuta cuando el servidor solicita cerrar el canal de forma ordenada
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        Log.d("SCADA_WS", "Cierre de conexión solicitado por el servidor: $reason")
    }

    // Se ejecuta cuando la conexión se cierra por completo
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        onConnectionStatusChanged(false, "Conexión cerrada")
    }

    // Se ejecuta si hay un corte de internet o el servidor FastAPI se apaga
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e("SCADA_WS", "Falla crítica en el canal WebSocket: ${t.message}", t)
        onConnectionStatusChanged(false, "Falla de red: ${t.message}")
    }
}