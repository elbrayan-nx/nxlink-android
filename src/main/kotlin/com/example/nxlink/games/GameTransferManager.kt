package com.example.nxlink.games

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── Transfer States ──────────────────────────────────────────────────────────
sealed interface GameTransferState {
    object Idle : GameTransferState
    data class Preparing(val uri: Uri) : GameTransferState
    data class StartingServer(val port: Int) : GameTransferState
    object WaitingForAwoo : GameTransferState
    data class Transferring(val ip: String, val progress: TransferProgress) : GameTransferState
    object Installing : GameTransferState
    data class Success(val ip: String, val bytesSent: Long) : GameTransferState
    data class Error(val message: String) : GameTransferState
    data class Cancelled(val message: String) : GameTransferState
}

// ─── Game Transfer Manager ────────────────────────────────────────────────────
/**
 * Coordinador principal para transferencias de juegos.
 * Maneja el servidor HTTP y la comunicación con Awoo.
 */
class GameTransferManager(
    private val context: Context,
    private val onStateChange: (GameTransferState) -> Unit,
    private val onProgress: (TransferProgress) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var serverJob: Job? = null
    private var awooJob: Job? = null
    private var gameServer: HttpGameServer? = null
    private var awooClient: AwooClient? = null

    private var currentUri: Uri? = null
    private var currentFileName: String? = null
    private var currentFileSize: Long = 0L
    private var currentSwitchIp: String? = null
    private var isManualStop = false
    private var isSuccessEmitted = false

    /**
     * Inicia la transferencia de un juego
     */
    fun startTransfer(uri: Uri, fileName: String, fileSize: Long, switchIp: String): Boolean {
        if (gameServer?.isRunning() == true || awooClient?.isConnected() == true) {
            onStateChange(GameTransferState.Error("Transfer already in progress"))
            return false
        }

        isManualStop = false
        isSuccessEmitted = false
        currentUri = uri
        currentFileName = fileName
        currentFileSize = fileSize
        currentSwitchIp = switchIp

        // Validar IP usando NetworkUtils
        if (!isValidIPv4(switchIp)) {
            onStateChange(GameTransferState.Error("Invalid IP address: $switchIp"))
            return false
        }

        onStateChange(GameTransferState.Preparing(uri))
        Log.i(TAG, "Starting transfer to $switchIp, file: $fileName, size: $fileSize bytes")

        // Iniciar servidor HTTP primero
        startHttpServer(uri, fileName, fileSize)

        return true
    }

    /**
     * Inicia el servidor HTTP
     */
    private fun startHttpServer(uri: Uri, fileName: String, fileSize: Long) {
        val server = HttpGameServer(
            context = context,
            onStateChange = { state ->
                when (state) {
                    is HttpServerState.Starting -> {
                        onStateChange(GameTransferState.StartingServer(state.port))
                        // Una vez iniciado, esperar a que Awoo se conecte
                        scope.launch {
                            delay(200) // Pequeño delay para asegurar que el servidor esté listo
                            startAwooConnection()
                        }
                    }
                    is HttpServerState.Running -> {
                        // Servidor listo, ya se inició AwooConnection
                    }
                    is HttpServerState.Stopped -> {
                        // Si ya se transfirieron todos los bytes, ignorar el cierre del servidor
                        val alreadyFinished = isSuccessEmitted || ((gameServer?.transferredBytes ?: 0) >= currentFileSize && currentFileSize > 0)
                        
                        if (this@GameTransferManager.scope.isActive && !isManualStop && !alreadyFinished) {
                            onStateChange(GameTransferState.Error("Server stopped unexpectedly"))
                        }
                    }
                    is HttpServerState.Error -> {
                        // Si ya se transfirieron todos los bytes, ignorar el error del servidor (a veces el Switch cierra mal el socket)
                        val alreadyFinished = isSuccessEmitted || ((gameServer?.transferredBytes ?: 0) >= currentFileSize && currentFileSize > 0)
                        
                        if (!alreadyFinished) {
                            onStateChange(GameTransferState.Error("Server error: ${state.message}"))
                        } else {
                            Log.i(TAG, "Ignoring server error after successful transfer: ${state.message}")
                        }
                    }
                    HttpServerState.Idle -> {}
                }
            },
            onProgress = { progress ->
                onProgress(progress)
                
                // Si estamos en estado de espera pero ya hay progreso, transicionar a Transferring
                // Esto asegura que la UI se actualice aunque no hayamos recibido el callback de Awoo
                if (progress.bytesTransferred > 0 && progress.bytesTransferred < currentFileSize) {
                    val switchIp = currentSwitchIp ?: return@HttpGameServer
                    onStateChange(GameTransferState.Transferring(switchIp, progress))
                }

                // Verificar si la transferencia se completó vía HTTP
                if (progress.bytesTransferred >= currentFileSize && currentFileSize > 0 && !isSuccessEmitted) {
                    isSuccessEmitted = true
                    val switchIp = currentSwitchIp ?: return@HttpGameServer
                    onStateChange(GameTransferState.Success(switchIp, progress.bytesTransferred))
                    Log.i(TAG, "HTTP Transfer complete: ${progress.bytesTransferred} bytes sent")
                    
                    // Cerrar servidor después de un delay para permitir que el Switch cierre la conexión
                    scope.launch {
                        delay(2000)
                        stopServer()
                    }
                }
            }
        )

        gameServer = server
        val started = server.start(uri, fileName, fileSize)
        
        if (!started) {
            onStateChange(GameTransferState.Error("Failed to start HTTP server"))
        }
    }

    /**
     * Inicia conexión con Awoo en la Switch
     */
    private fun startAwooConnection() {
        val switchIp = currentSwitchIp ?: return
        val port = gameServer?.port ?: return
        val fileName = currentFileName ?: return

        scope.launch {
            // OBTENER LA IP LOCAL DEL ANDROID EN HILO DE IO
            val localIp = withContext(Dispatchers.IO) { getLocalIpAddress(context) }
            
            if (localIp == null) {
                onStateChange(GameTransferState.Error("No WiFi connection found. Ensure both devices are on the same network."))
                stopServer()
                return@launch
            }

            val url = buildFileUrl(localIp, port, fileName)
            val urls = listOf(url)

            Log.i(TAG, "Connecting to Awoo on $switchIp:2000 with URL: $url")

            val awoo = AwooClient(switchIp) { state ->
                when (state) {
                    is AwooTransferState.Connected -> {
                        onStateChange(GameTransferState.WaitingForAwoo)
                        Log.i(TAG, "Connected to Awoo")
                    }
                    is AwooTransferState.SendingUrls -> {
                        onStateChange(GameTransferState.Transferring(switchIp, TransferProgress(0, currentFileSize)))
                        Log.i(TAG, "Sent ${state.urlCount} URLs to Awoo")
                    }
                    is AwooTransferState.WaitingForDownload -> {
                        onStateChange(GameTransferState.WaitingForAwoo)
                        Log.i(TAG, "Awoo received URLs, waiting for download to start")
                    }
                    is AwooTransferState.UrlSent -> {
                        Log.i(TAG, "URL delivery confirmed")
                    }
                    is AwooTransferState.Error -> {
                        onStateChange(GameTransferState.Error("Awoo error: ${state.message}"))
                        stopServer()
                    }
                    is AwooTransferState.Connecting -> {}
                    AwooTransferState.Idle -> {}
                }
            }

            awooClient = awoo
            awooJob = scope.launch {
                val success = awoo.sendUrls(urls)
                if (!success && this@GameTransferManager.scope.isActive) {
                    onStateChange(GameTransferState.Error("Transfer failed"))
                }
            }
        }
    }

    /**
     * Detiene el servidor HTTP
     */
    private fun stopServer() {
        isManualStop = true
        gameServer?.stop()
    }

    /**
     * Cancela la transferencia actual
     */
    fun cancel() {
        isManualStop = true
        gameServer?.stop()
        awooClient?.cancel()
        
        scope.launch {
            if (this@GameTransferManager.scope.isActive) {
                onStateChange(GameTransferState.Cancelled("Transfer cancelled by user"))
            }
        }
    }

    /**
     * Limpia recursos
     */
    fun destroy() {
        isManualStop = true
        gameServer?.destroy()
        awooClient?.cancel()
        // No podemos cancelar el scope directamente, solo cancelamos los jobs
        serverJob?.cancel()
        awooJob?.cancel()
    }

    companion object {
        private const val TAG = "GameTransferManager"
    }
}
