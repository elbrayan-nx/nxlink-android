# NXLink Android 🎮

[Español](#español) | [English](#english)

---

<a name="español"></a>
## Español

Envía archivos Homebrew (`.nro`) e instala juegos (`.nsp`, `.xci`) en tu Nintendo Switch directamente desde tu Android — sin cables y sin necesitar una computadora.

### 🚀 ¿Qué hace esta app?
NXLink es una herramienta "todo en uno" para usuarios de Switch que quieren gestionar su consola desde el móvil:
1.  **Homebrew Loader**: Transfiere y ejecuta archivos `.nro` al instante usando el protocolo **nxlink Netloader**.
2.  **Game Installer**: Instala juegos, actualizaciones y DLCs (`.nsp` / `.xci`) de forma remota a través de la red local, compatible con instaladores como **Awoo Installer** o **Tinfoil**.

### 📋 Requisitos
- Android 8.0 o superior.
- Nintendo Switch con **Atmosphere** u otro CFW.
- Teléfono y Switch conectados a la **misma red WiFi**.
- Para Juegos: Un instalador en la Switch con soporte de red (Awoo Installer recomendado).

### 🛠️ Cómo usarla
**Para archivos Homebrew (.nro)**
1. Abre el **Homebrew Menu** en tu Switch y presiona **Y**. Verás la IP en pantalla.
2. En la app, busca la sección **NRO FILE** y selecciona tu archivo.
3. Ingresa la IP de la Switch y toca **"Send to Switch"**.

**Para Juegos (.nsp / .xci)**
1. Abre **Awoo Installer** (o similar) en tu Switch.
2. Selecciona **"Install over LAN or internet"**.
3. En la app, busca la sección **GAMES (NSP/XCI)** y selecciona el juego.
4. Ingresa la IP de la Switch y toca **"Install Game"**.

---

<a name="english"></a>
## English

Send Homebrew files (`.nro`) and install games (`.nsp`, `.xci`) to your Nintendo Switch directly from your Android device — wire-free and no computer needed.

### 🚀 Features
1.  **Homebrew Loader**: Instantly transfer and launch `.nro` files using the **nxlink Netloader** protocol.
2.  **Game Installer**: Remotely install games, updates, and DLCs (`.nsp` / `.xci`) over your local network. Compatible with installers like **Awoo Installer** or **Tinfoil**.

### 📋 Requirements
- Android 8.0 or higher.
- Nintendo Switch with **Atmosphere** or other CFW.
- Phone and Switch must be connected to the **same WiFi network**.
- For Games: A Switch installer with network support (Awoo Installer recommended).

### 🛠️ How to use
**For Homebrew files (.nro)**
1. Open the **Homebrew Menu** on your Switch and press **Y**. The IP will appear on the screen.
2. In the app, go to the **NRO FILE** section and select your file.
3. Enter the Switch IP and tap **"Send to Switch"**.

**For Games (.nsp / .xci)**
1. Open **Awoo Installer** (or similar) on your Switch.
2. Select **"Install over LAN or internet"**.
3. In the app, go to the **GAMES (NSP/XCI)** section and select your game.
4. Enter the Switch IP and tap **"Install Game"**.

---

### 📥 Download / Descarga
👉 Go to the [Releases](https://github.com/el-brayan/nxlink-android/releases) section and download the latest `app-debug.apk`.

---

### ⚙️ Tech Stack / Detalles Técnicos
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Protocols:** 
  - Netloader TCP (port 28280) for NROs.
  - Internal HTTP Server + Tinfoil Protocol (port 2000) for Games.
- **Developed with:** Android Studio + Gemini AI

---

### 🤝 Credits / Créditos
- NRO protocol based on [nx-hbmenu](https://github.com/switchbrew/nx-hbmenu) by Switchbrew.
- Network logic compatible with Tinfoil/Awoo standard for remote installations.
