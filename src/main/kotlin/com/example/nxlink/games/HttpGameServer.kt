package com.example.nxlink.games

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

// ─── Constants ────────────────────────────────────────────────────────────────
private const val TAG = "HttpGameServer"
private const val CHUNK_SIZE = 8 * 1024  // 8KB chunks for streaming
private const val DEFAULT_PORT = 8080

// ─── Transfer Progress ────────────────────────────────────────────────────────
data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val startTime: Long = System.currentTimeMillis()
) {
    fun getTransferRateBytesPerSecond(): Long {
        val elapsedMillis = System.currentTimeMillis() - startTime
        if (elapsedMillis <= 0) return 0
        return (bytesTransferred * 1000 / elapsedMillis)
    }
    fun getProgressPercent(): Int = if (totalBytes > 0) (bytesTransferred * 100 / totalBytes).toInt() else 0
}

// ─── Server State ─────────────────────────────────────────────────────────────
sealed interface HttpServerState {
    object Idle : HttpServerState
    data class Starting(val port: Int) : HttpServerState
    data class Running(val port: Int) : HttpServerState
    data class Stopped(val port: Int) : HttpServerState
    data class Error(val port: Int, val message: String) : HttpServerState
}

// ─── Game Server ──────────────────────────────────────────────────────────────
class HttpGameServer(
    private val context: Context,
    private val onStateChange: (HttpServerState) -> Unit,
    private val onProgress: (TransferProgress) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverSocket: ServerSocket? = null
    private var currentFileUri: Uri? = null
    private var currentFileName: String? = null
    private var currentFileSize: Long = 0L
    private val bytesTransferred = AtomicLong(0)
    private var isRunning = false

    val port: Int
        get() = if (isRunning && serverSocket != null) serverSocket!!.localPort else -1

    val transferredBytes: Long
        get() = bytesTransferred.get()

    /**
     * Inicia el servidor HTTP con el archivo especificado
     */
    fun start(uri: Uri, fileName: String, fileSize: Long): Boolean {
        if (isRunning) {
            Log.w(TAG, "Server is already running")
            return false
        }

        currentFileUri = uri
        currentFileName = fileName
        currentFileSize = fileSize
        bytesTransferred.set(0)
        isRunning = true // Mark as starting/running before launching coroutine

        scope.launch {
            var port = DEFAULT_PORT
            try {
                port = getAvailablePort(DEFAULT_PORT)
                onStateChange(HttpServerState.Starting(port))
                
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                onStateChange(HttpServerState.Running(port))
                Log.i(TAG, "Server started on port $port")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            Log.i(TAG, "Incoming connection from ${clientSocket.inetAddress}")
                            scope.launch {
                                try {
                                    handleRequest(clientSocket)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error handling request", e)
                                } finally {
                                    clientSocket.close()
                                }
                            }
                        }
                    } catch (e: java.net.SocketException) {
                        // Si isRunning es false, el socket se cerró intencionalmente
                        if (isRunning) {
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onStateChange(HttpServerState.Error(port, "Failed to start server: ${e.message}"))
                    Log.e(TAG, "Failed to start server", e)
                }
            }
        }

        return true
    }

    /**
     * Detiene el servidor HTTP
     */
    fun stop() {
        if (isRunning) {
            isRunning = false
            serverSocket?.close()
            onStateChange(HttpServerState.Stopped(port))
            Log.i(TAG, "Server stopped")
        }
    }

    /**
     * Maneja la solicitud HTTP entrante
     */
    private suspend fun handleRequest(clientSocket: java.net.Socket) {
        val inputStream = clientSocket.getInputStream()
        val outputStream = clientSocket.getOutputStream()

        try {
            // Leer la primera línea de la solicitud
            val requestLine = inputStream.readLine()
            if (requestLine.isNullOrBlank()) return

            Log.i(TAG, "Request: $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val requestedPath = parts[1]

            // Normalizar el path: Decodificar URL y quitar query params
            // Awoo puede enviar ?size=... o espacios como %20
            val decodedPath = try {
                URLDecoder.decode(requestedPath, "UTF-8")
            } catch (e: Exception) {
                requestedPath
            }
            
            val cleanPath = decodedPath.substringBefore("?").substringAfterLast("/")

            Log.d(TAG, "Method: $method, Clean Path: $cleanPath, Expected: $currentFileName")

            if (cleanPath == currentFileName && (method == "GET" || method == "HEAD")) {
                if (method == "GET") {
                    handleGetFile(inputStream, outputStream)
                } else {
                    handleHeadFile(outputStream)
                }
            } else {
                Log.w(TAG, "404 Not Found: $cleanPath")
                sendResponse(outputStream, 404, "Not Found", "404 - File not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            try {
                sendResponse(outputStream, 500, "Internal Server Error", "Error: ${e.message}")
            } catch (ignore: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Maneja la solicitud GET para obtener el archivo
     */
    private suspend fun handleGetFile(inputStream: InputStream, outputStream: OutputStream) {
        val fileInputStream: InputStream

        try {
            fileInputStream = context.contentResolver.openInputStream(currentFileUri!!) ?: throw IOException("Cannot open file")
        } catch (e: Exception) {
            sendResponse(outputStream, 404, "Not Found", "Error opening file: ${e.message}")
            return
        }

        try {
            // Leer headers de la solicitud
            val headers = readHeaders(inputStream)
            
            Log.d(TAG, "Request Headers:")
            headers.forEach { (key, value) -> Log.d(TAG, "  $key: $value") }

            // Soporte para Range Requests
            val rangeHeader = headers["range"]
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                handleRangeRequest(fileInputStream, outputStream, rangeHeader)
            } else {
                // Request completo
                sendFullFile(fileInputStream, outputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file", e)
            sendResponse(outputStream, 500, "Internal Server Error", "Error: ${e.message}")
        } finally {
            fileInputStream.close()
        }
    }

    /**
     * Maneja la solicitud HEAD
     */
    private suspend fun handleHeadFile(outputStream: OutputStream) {
        sendResponseHeaders(outputStream, 200, currentFileSize, currentFileName!!)
        outputStream.flush()
    }

    /**
     * Maneja Range Requests para soportar reanudación de descargas
     */
    private suspend fun handleRangeRequest(
        inputStream: InputStream,
        outputStream: OutputStream,
        rangeHeader: String
    ) {
        try {
            // Parsear el range header (ej: "bytes=0-1023" o "bytes=1024-")
            val rangePattern = "bytes=(\\d+)-(\\d*)".toRegex()
            val match = rangePattern.find(rangeHeader)
            
            if (match == null) {
                sendResponse(outputStream, 416, "Range Not Satisfiable", "Invalid range header")
                return
            }

            val start = match.groupValues[1].toLong()
            val end = if (match.groupValues[2].isEmpty()) currentFileSize - 1 else match.groupValues[2].toLong()
            
            // Validar rangos
            if (start >= currentFileSize || end < start) {
                sendResponse(outputStream, 416, "Range Not Satisfiable", "Range not satisfiable")
                return
            }

            val contentLength = end - start + 1L
            
            // Skip to start position reliably
            var totalSkipped = 0L
            while (totalSkipped < start) {
                val skipped = inputStream.skip(start - totalSkipped)
                if (skipped <= 0) break
                totalSkipped += skipped
            }
            
            // Responder con 206 Partial Content
            sendPartialResponse(outputStream, start, currentFileSize, contentLength)
            
            Log.i(TAG, "Serving Range Request: $start to $end ($contentLength bytes)")

            // Enviar el contenido
            val buffer = ByteArray(CHUNK_SIZE)
            var remaining = contentLength
            var read: Int

            while (remaining > 0 && isRunning) {
                read = inputStream.read(buffer, 0, min(CHUNK_SIZE.toLong(), remaining).toInt())
                if (read == -1) break
                
                outputStream.write(buffer, 0, read)
                outputStream.flush()
                val totalSent = bytesTransferred.addAndGet(read.toLong())
                remaining -= read

                onProgress(TransferProgress(totalSent, currentFileSize))
            }
            
            Log.d(TAG, "Range request finished. Sent: ${contentLength - remaining} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling range request", e)
            try {
                sendResponse(outputStream, 500, "Internal Server Error", "Range request error: ${e.message}")
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Envia el archivo completo
     */
    private suspend fun sendFullFile(inputStream: InputStream, outputStream: OutputStream) {
        Log.i(TAG, "Serving full file: $currentFileName ($currentFileSize bytes)")
        
        // Enviar headers
        sendResponseHeaders(outputStream, 200, currentFileSize, currentFileName!!)
        
        // Enviar el contenido
        val buffer = ByteArray(CHUNK_SIZE)
        var read = 0

        while (isRunning && inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
            outputStream.flush()
            val totalSent = bytesTransferred.addAndGet(read.toLong())
            onProgress(TransferProgress(totalSent, currentFileSize))
        }
        
        Log.i(TAG, "Full file finished. Total sent: ${bytesTransferred.get()} bytes")
    }

    /**
     * Envia headers de respuesta
     */
    private fun sendResponseHeaders(
        outputStream: OutputStream,
        statusCode: Int,
        contentLength: Long,
        fileName: String
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }

        val response = StringBuilder()
        response.append("HTTP/1.1 $statusCode $statusText\r\n")
        response.append("Content-Type: application/octet-stream\r\n")
        response.append("Content-Disposition: attachment; filename=\"$fileName\"\r\n")
        response.append("Accept-Ranges: bytes\r\n")
        response.append("Content-Length: $contentLength\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")

        outputStream.write(response.toString().toByteArray())
        outputStream.flush()
    }

    /**
     * Envia respuesta parcial (Range Request)
     */
    private suspend fun sendPartialResponse(
        outputStream: OutputStream,
        start: Long,
        totalSize: Long,
        contentLength: Long
    ) {
        val end = if (start + contentLength - 1 < totalSize - 1) start + contentLength - 1 else totalSize - 1
        
        val response = StringBuilder()
        response.append("HTTP/1.1 206 Partial Content\r\n")
        response.append("Content-Type: application/octet-stream\r\n")
        response.append("Accept-Ranges: bytes\r\n")
        response.append("Content-Length: $contentLength\r\n")
        response.append("Content-Range: bytes $start-$end/$totalSize\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")

        outputStream.write(response.toString().toByteArray())
        outputStream.flush()
    }

    /**
     * Envia una respuesta HTTP simple
     */
    private suspend fun sendResponse(
        outputStream: OutputStream,
        statusCode: Int,
        statusText: String,
        body: String
    ) {
        val response = StringBuilder()
        response.append("HTTP/1.1 $statusCode $statusText\r\n")
        response.append("Content-Type: text/plain\r\n")
        response.append("Content-Length: ${body.length}\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        response.append(body)

        outputStream.write(response.toString().toByteArray())
        outputStream.flush()
    }

    /**
     * Lee los headers de la solicitud HTTP
     */
    private fun readHeaders(inputStream: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        while (true) {
            val line = inputStream.readLine()
            if (line.isNullOrBlank()) break
            
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim().lowercase()] = parts[1].trim()
            }
        }
        
        return headers
    }

    /**
     * Lee una línea de datos del socket (terminada en \n o \r\n)
     */
    private fun InputStream.readLine(): String? {
        val buffer = StringBuilder()
        var b: Int
        var hasContent = false
        
        while (true) {
            b = read()
            if (b == -1) return if (hasContent) buffer.toString() else null
            hasContent = true
            
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                buffer.append(b.toChar())
            }
        }
        return buffer.toString()
    }

    /**
     * Verifica si el servidor está ejecutándose
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Limpia recursos
     */
    fun destroy() {
        scope.cancel()
        stop()
    }
}
