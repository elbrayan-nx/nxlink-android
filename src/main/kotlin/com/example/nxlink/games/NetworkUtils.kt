package com.example.nxlink.games

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

// ─── Constants ────────────────────────────────────────────────────────────────
internal const val AAWOO_INSTALLER_PORT = 2000  // Awoo Network Install listening port
// DEFAULT_HTTP_PORT moved to HttpGameServer.kt to avoid conflict

// ─── Network Utilities ───────────────────────────────────────────────────────
/**
 * Obtiene la IP local del dispositivo en la red WiFi activa.
 * @return IP en formato String (ej: "192.168.1.100") o null si no hay conexión
 */
internal fun getLocalIpAddress(context: Context): String? {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    // Verificar si hay conexión WiFi
    val network = connectivity.activeNetwork ?: return null
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
    
    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return null
    }
    
    try {
        // Obtener interfaces de red
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            // Filtrar solo interfaces WiFi y loopback
            if (intf.name.contains("wlan") || intf.name.contains("eth")) {
                val inetAddrs = intf.inetAddresses
                while (inetAddrs.hasMoreElements()) {
                    val addr = inetAddrs.nextElement()
                   if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
    return addr.hostAddress
}
                }
            }
        }
    } catch (e: SocketException) {
        e.printStackTrace()
    }
    
    return null
}

/**
 * Verifica si el puerto está disponible (no está en uso)
 */
internal fun isPortAvailable(port: Int): Boolean {
    return try {
        val socket = java.net.ServerSocket(port)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Obtiene un puerto disponible aleatorio entre 8000 y 9000
 */
internal fun getAvailablePort(startPort: Int): Int {
    var port = startPort
    while (port < 9000) {
        if (isPortAvailable(port)) {
            return port
        }
        port++
    }
    return startPort
}

/**
 * Verifica si el dispositivo tiene conexión a Internet
 */
internal fun hasInternetConnection(context: Context): Boolean {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        @Suppress("DEPRECATION")
        return connectivity.activeNetworkInfo?.isConnected ?: false
    }
}

/**
 * Valida si una cadena es una dirección IPv4 válida
 */
internal fun isValidIPv4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

/**
 * Genera la URL completa para acceder al archivo desde la Switch.
 * Codifica el nombre del archivo para manejar espacios y caracteres especiales.
 */
internal fun buildFileUrl(ip: String, port: Int, fileName: String): String {
    val encodedName = Uri.encode(fileName)
    return "http://$ip:$port/$encodedName"
}
