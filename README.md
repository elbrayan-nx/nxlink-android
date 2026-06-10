NXLink Android 🎮
Envía archivos .nro de Homebrew a tu Nintendo Switch directamente desde tu Android — sin necesitar computadora.

¿Qué hace esta app?
Conecta tu teléfono Android con tu Nintendo Switch por WiFi y transfiere archivos .nro usando el protocolo nxlink Netloader. El archivo se transfiere y se abre automáticamente en el Switch.

Requisitos

Android 8.0 o superior
Nintendo Switch con Atmosphere u otro CFW instalado
Homebrew Menu con soporte para Netloader (nx-hbmenu)
Teléfono y Switch conectados al mismo WiFi


Cómo usarla

Descarga e instala el APK en tu Android (ver sección de Releases)
En tu Android, abre la app NXLink
Toca Browse y selecciona el archivo .nro que quieres enviar
En tu Nintendo Switch, abre el Homebrew Menu y presiona Y para activar el Netloader
El Switch mostrará su dirección IP en pantalla (ejemplo: 192.168.1.21)
Ingresa esa IP en la app y toca "Send to Switch"
¡El archivo se transfiere y se abre automáticamente en el Switch! ✅


Nota: El Switch solo escucha conexiones por unos segundos después de presionar Y. Si da error, vuelve a presionar Y en el Switch y luego toca Send en la app.


Descarga
👉 Ve a la sección Releases y descarga el archivo app-debug.apk
Para instalar el APK en Android:

Abre el archivo descargado
Si te pide permiso para "instalar apps de fuentes desconocidas", acéptalo
Instala y listo


Tecnología

Lenguaje: Kotlin
UI: Jetpack Compose (Material 3)
Protocolo: nxlink Netloader TCP (puerto 28280)
Desarrollado con: Android Studio + Claude AI

Créditos
Protocolo basado en nx-hbmenu de Switchbrew.
