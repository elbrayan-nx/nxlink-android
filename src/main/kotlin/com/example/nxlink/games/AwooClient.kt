package com.example.nxlink.games

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

// ─── Constants ────────────────────────────────────────────────────────────────
private const val TAG = "AwooClient"
private const val TIMEOUT_MS = 10_000
private const val AWOO_NETWORK_INSTALL_PORT = 2000

// ─── Awoo Transfer States ─────────────────────────────────────────────────────
sealed interface AwooTransferState {
    object Idle : AwooTransferState
    data class Connecting(val switchIp: String) : AwooTransferState
    data class Connected(val switchIp: String) : AwooTransferState
    data class SendingUrls(val switchIp: String, val urlCount: Int) : AwooTransferState
    data class WaitingForDownload(val switchIp: String) : AwooTransferState
    data class UrlSent(val switchIp: String) : AwooTransferState
    data class Error(val switchIp: String, val message: String) : AwooTransferState
}

// ─── Awoo Client ──────────────────────────────────────────────────────────────
/**
 * Cliente para comunicarse con Awoo Installer vía TCP puerto 2000.
 * 
 * Protocolo Tinfoil/Awoo:
 * 1. Switch escucha en puerto 2000 (Network Install mode)
 * 2. Cliente se conecta
 * 3. Envía: [4 bytes big-endian tamaño buffer][ URLs separadas por \n ]
 * 4. Switch descarga archivos desde URLs y comienza instalación
 */
class AwooClient(
    private val switchIp: String,
    private val onStateChange: (AwooTransferState) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    /**
     * Conecta con Awoo en la Switch y envía las URLs
     * @return true si la transferencia se completó, false en caso de error
     */
    suspend fun sendUrls(urls: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            onStateChange(AwooTransferState.Connecting(switchIp))
            
            // Conectar al Switch puerto 2000
            val socket = Socket().apply {
                connect(InetSocketAddress(switchIp, AWOO_NETWORK_INSTALL_PORT), TIMEOUT_MS)
                soTimeout = TIMEOUT_MS
            }
            
            this@AwooClient.socket = socket
            outputStream = DataOutputStream(socket.getOutputStream())
            inputStream = DataInputStream(socket.getInputStream())
            
            onStateChange(AwooTransferState.Connected(switchIp))
            Log.i(TAG, "Connected to Awoo on $switchIp:2000")

            // Preparar el buffer de URLs.
            // IMPORTANTE: Awoo/Tinfoil a menudo requieren que la lista termine en \n
            val urlBuffer = urls.joinToString("\n") + "\n"
            val urlBytes = urlBuffer.toByteArray(Charsets.UTF_8)
            val bufferSize = urlBytes.size
            
            // Enviar tamaño del buffer (4 bytes Big-endian, requerido por Awoo/Tinfoil)
            outputStream?.writeByte((bufferSize shr 24) and 0xFF)
            outputStream?.writeByte((bufferSize shr 16) and 0xFF)
            outputStream?.writeByte((bufferSize shr 8) and 0xFF)
            outputStream?.writeByte(bufferSize and 0xFF)
            
            onStateChange(AwooTransferState.SendingUrls(switchIp, urls.size))
            Log.i(TAG, "Sent URL buffer size: $bufferSize bytes")

            // Enviar URLs
            outputStream?.write(urlBytes)
            outputStream?.flush()
            
            Log.i(TAG, "Sent ${urls.size} URLs to Awoo")
            onStateChange(AwooTransferState.WaitingForDownload(switchIp))

            // Awoo no envía respuesta después de recibir URLs
            // La instalación ocurre en paralelo en la Switch
            // Esperar un poco para confirmar que la conexión se mantuvo
            delay(500)

            // Cerrar conexión
            onStateChange(AwooTransferState.UrlSent(switchIp))
            Log.i(TAG, "URLs sent successfully")

            true
        } catch (e: java.net.ConnectException) {
            val message = if (e.message?.contains("Connection refused") == true) {
                "Connection refused. Is Awoo in 'Network Install' mode?"
            } else {
                "Connect error: ${e.message}"
            }
            onStateChange(AwooTransferState.Error(switchIp, message))
            Log.e(TAG, "Connect error", e)
            false
        } catch (e: SocketTimeoutException) {
            onStateChange(AwooTransferState.Error(switchIp, "Connection timed out. Is Awoo in Network Install mode?"))
            Log.e(TAG, "Connection timed out", e)
            false
        } catch (e: IOException) {
            onStateChange(AwooTransferState.Error(switchIp, "Network error: ${e.message}"))
            Log.e(TAG, "Network error", e)
            false
        } catch (e: Exception) {
            onStateChange(AwooTransferState.Error(switchIp, "Unexpected error: ${e.message}"))
            Log.e(TAG, "Unexpected error", e)
            false
        } finally {
            cleanup()
        }
    }

    /**
     * Cierra la conexión
     */
    fun cancel() {
        cleanup()
    }

    /**
     * Limpia recursos
     */
    private fun cleanup() {
        outputStream?.close()
        inputStream?.close()
        socket?.close()
        
        outputStream = null
        inputStream = null
        socket = null
    }

    /**
     * Verifica si el cliente está conectado
     */
    fun isConnected(): Boolean = socket?.isConnected == true

    /**
     * Obtiene el estado actual
     */
    fun getState(): AwooTransferState {
        if (socket == null) return AwooTransferState.Idle
        if (outputStream == null) return AwooTransferState.Error(switchIp, "Not initialized")
        return AwooTransferState.Connected(switchIp)
    }
}
