package com.example.nxlink

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.example.nxlink.games.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

// ─── nxlink protocol constants ────────────────────────────────────────────────
private const val NXLINK_PORT = 28280
private const val CONNECT_TIMEOUT_MS = 5_000
private const val PREFS_NAME = "nxlink_prefs"
private const val PREF_IP = "switch_ip"

// ─── Transfer state ───────────────────────────────────────────────────────────
sealed class TransferState {
    object Idle : TransferState()
    data class Progress(val bytesSent: Long, val totalBytes: Long) : TransferState()
    data class Success(val bytesSent: Long) : TransferState()
    data class Error(val message: String) : TransferState()
}

// ─── Theme colours ─────────────────────────────────────────────────────────────
private val NxRed = Color(0xFFE4000F)   // Nintendo red
private val NxDark = Color(0xFF0D0D0D)
private val NxSurface = Color(0xFF1A1A1A)
private val NxCard = Color(0xFF242424)
private val NxBorder = Color(0xFF333333)
private val NxTextPrimary = Color(0xFFF2F2F2)
private val NxTextSecondary = Color(0xFF8A8A8A)
private val NxGreen = Color(0xFF22C55E)

// ─── Status Style for Game States ─────────────────────────────────────────────
private fun getStatusStyleForGame(state: GameTransferState): StatusStyle {
    val (bg, border, text, icon, label) = when (state) {
        is GameTransferState.Idle -> StatusStyle(
            Color.Transparent,
            Color.Transparent,
            Color.Transparent,
            "",
            ""
        )

        is GameTransferState.Preparing -> StatusStyle(
            bg = Color(0xFF1F1F0D),
            border = Color(0xFF4D4D1A),
            text = Color(0xFFFFD700),
            icon = " Prep",
            label = "Preparing game file"
        )

        is GameTransferState.StartingServer -> StatusStyle(
            bg = Color(0xFF0D1F1F),
            border = Color(0xFF1A4D4D),
            text = Color(0xFF40E0D0),
            icon = "Server",
            label = "Starting HTTP server..."
        )

        is GameTransferState.WaitingForAwoo -> StatusStyle(
            bg = Color(0xFF0D1F2F),
            border = Color(0xFF1A4D6D),
            text = Color(0xFF00BFFF),
            icon = " Awoo",
            label = "Waiting for Awoo connection..."
        )

        is GameTransferState.Transferring -> StatusStyle(
            bg = Color(0xFF0D1F17),
            border = Color(0xFF1A4D2E),
            text = NxTextPrimary,
            icon = "↑",
            label = buildString {
                val pct = state.progress.getProgressPercent()
                append("Transferring... $pct%")
                val total = state.progress.totalBytes
                val sent = state.progress.bytesTransferred
                if (total > 0) {
                    append("  (${sent.toKb()} / ${total.toKb()})")
                }
                val rate = state.progress.getTransferRateBytesPerSecond()
                if (rate > 0) {
                    append("  ${rate.toKb()}/s")
                }
            }
        )

        is GameTransferState.Installing -> StatusStyle(
            bg = Color(0xFF0D1F17),
            border = Color(0xFF1A4D2E),
            text = Color(0xFF98FB98),
            icon = "Install",
            label = "Installing on Switch..."
        )

        is GameTransferState.Success -> StatusStyle(
            bg = Color(0xFF0D1F17),
            border = NxGreen.copy(alpha = 0.4f),
            text = NxGreen,
            icon = "✓",
            label = "Transfer complete — ${state.bytesSent.toKb()} sent"
        )

        is GameTransferState.Error -> StatusStyle(
            bg = Color(0xFF1F0D0D),
            border = NxRed.copy(alpha = 0.4f),
            text = Color(0xFFFF6B6B),
            icon = "✕",
            label = state.message
        )

        is GameTransferState.Cancelled -> StatusStyle(
            bg = Color(0xFF1F1F0D),
            border = Color(0xFF4D4D1A),
            text = Color(0xFFFFD700),
            icon = "✕",
            label = state.message
        )
    }
    return StatusStyle(bg, border, text, icon, label)
}

// ─── Activity ─────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NxRed,
                    background = NxDark,
                    surface = NxSurface,
                    onPrimary = Color.White,
                    onBackground = NxTextPrimary,
                    onSurface = NxTextPrimary,
                )
            ) {
                NxLinkScreen()
            }
        }
    }
}

// ─── Main Screen ──────────────────────────────────────────────────────────────
@Composable
fun NxLinkScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // IP Address (shared between NRO and Games)
    var ipAddress by remember { mutableStateOf(prefs.getString(PREF_IP, "") ?: "") }

    // NRO State
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("No file selected") }
    var transferState by remember { mutableStateOf<TransferState>(TransferState.Idle) }

    // Games State
    var gameFileUri by remember { mutableStateOf<Uri?>(null) }
    var gameFileName by remember { mutableStateOf("No game selected") }
    var gameState by remember { mutableStateOf<GameTransferState>(GameTransferState.Idle) }

    var gameTransferManager by remember { mutableStateOf<GameTransferManager?>(null) }

    // NRO File Picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            selectedName = uri.getFileName(context) ?: uri.lastPathSegment ?: "unknown.nro"
            transferState = TransferState.Idle
        }
    }

    // Games File Picker
    val gameFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            gameFileUri = uri
            gameFileName = uri.getFileName(context) ?: uri.lastPathSegment ?: "unknown.nsp"
            gameState = com.example.nxlink.games.GameTransferState.Idle
        }
    }

    val isTransferring = transferState is TransferState.Progress
    val canSend = selectedUri != null
            && ipAddress.trim().isNotEmpty()
            && !isTransferring

    // Game transfer button state
    val isGameTransferring =
        gameState !is GameTransferState.Idle && gameState !is GameTransferState.Success && gameState !is GameTransferState.Error && gameState !is GameTransferState.Cancelled
    val canInstallGame = gameFileUri != null
            && ipAddress.trim().isNotEmpty()
            && !isGameTransferring
            && ipAddress.trim().isValidIpv4()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NxDark)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Header ──
            NxHeader()

            // ── IP address input ──
            NxCard {
                SectionLabel("SWITCH IP ADDRESS")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            "192.168.1.x",
                            color = NxTextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        color = NxTextPrimary,
                        fontSize = 16.sp
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            prefs.edit { putString(PREF_IP, ipAddress.trim()) }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NxRed,
                        unfocusedBorderColor = NxBorder,
                        cursorColor = NxRed,
                        focusedTextColor = NxTextPrimary,
                        unfocusedTextColor = NxTextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    trailingIcon = {
                        if (ipAddress.isNotEmpty()) {
                            TextButton(onClick = {
                                prefs.edit { putString(PREF_IP, ipAddress.trim()) }
                                focusManager.clearFocus()
                            }) {
                                Text("Save", color = NxRed, fontSize = 12.sp)
                            }
                        }
                    }
                )
                if (ipAddress.trim().isNotEmpty() && !ipAddress.trim().isValidIpv4()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enter a valid IPv4 address",
                        color = NxRed.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }

            // ── NRO / Homebrew Section ──────────────────────────────────
            NxCard {
                SectionLabel("NRO FILE")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // File name display
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NxDark)
                            .border(1.dp, NxBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = selectedName,
                            color = if (selectedUri != null) NxTextPrimary else NxTextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Browse button
                    Button(
                        onClick = { filePicker.launch("*/*") },
                        enabled = !isTransferring,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NxCard,
                            contentColor = NxTextPrimary,
                            disabledContainerColor = NxCard.copy(alpha = 0.5f)
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(listOf(NxBorder, NxBorder))
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Browse", fontSize = 14.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Send button (NRO)
                Button(
                    onClick = {
                        val uri = selectedUri ?: return@Button
                        val ip = ipAddress.trim()
                        prefs.edit { putString(PREF_IP, ip) }
                        focusManager.clearFocus()
                        scope.launch {
                            sendNro(context, ip, uri) { state ->
                                transferState = state
                            }
                        }
                    },
                    enabled = canSend && ipAddress.trim().isValidIpv4(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NxRed,
                        contentColor = Color.White,
                        disabledContainerColor = NxRed.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                    )
                ) {
                    if (isTransferring) {
                        SpinnerIcon()
                        Spacer(Modifier.width(10.dp))
                        Text("Sending…", fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    } else {
                        Text(
                            "Send to Switch",
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Status panel (NRO)
                AnimatedVisibility(
                    visible = transferState !is TransferState.Idle,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        StatusPanel(transferState)
                    }
                }
            }

            // ── Visual Separation ──────────────────────────────────────────
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = NxBorder,
                thickness = 1.dp
            )

            // ── Games Section ──────────────────────────────────────────────
            NxCard {
                SectionLabel("GAMES (NSP/XCI)")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // File name display
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NxDark)
                            .border(1.dp, NxBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = gameFileName,
                            color = if (gameFileUri != null) NxTextPrimary else NxTextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Browse button
                    Button(
                        onClick = { gameFilePicker.launch("*/*") },
                        enabled = !isGameTransferring,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NxCard,
                            contentColor = NxTextPrimary,
                            disabledContainerColor = NxCard.copy(alpha = 0.5f)
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(listOf(NxBorder, NxBorder))
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Select", fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Install Game Button
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val uri = gameFileUri ?: return@Button
                            val ip = ipAddress.trim()
                            prefs.edit { putString(PREF_IP, ip) }
                            focusManager.clearFocus()

                            // Get file size
                            val fileSize = context.contentResolver.openFileDescriptor(uri, "r")
                                ?.use { it.statSize } ?: -1L
                            val fileName = gameFileName

                            // Create GameTransferManager
                            val manager = GameTransferManager(
                                context = context,
                                onStateChange = { state ->
                                    gameState = state
                                },
                                onProgress = { _ ->
                                    // Progress is already handled by GameTransferManager state changes
                                }
                            )
                            gameTransferManager = manager

                            // Start transfer
                            manager.startTransfer(uri, fileName, fileSize, ip)
                        },
                        enabled = canInstallGame,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NxRed,
                            contentColor = Color.White,
                            disabledContainerColor = NxRed.copy(alpha = 0.35f),
                            disabledContentColor = Color.White.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isGameTransferring) {
                            SpinnerIcon()
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Installing…",
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                        } else {
                            Text(
                                "Install Game",
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Cancel Button (only visible during transfer)
                    if (isGameTransferring) {
                        Button(
                            onClick = {
                                gameTransferManager?.cancel()
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .width(100.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF444444),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF444444).copy(alpha = 0.5f)
                            )
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }

                // Status panel (Games)
                AnimatedVisibility(
                    visible = gameState !is GameTransferState.Idle,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        StatusPanelForGame(gameState)
                    }
                }
            }
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────
@Composable
fun NxHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Red accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NxRed)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "nxlink",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = NxTextPrimary,
                letterSpacing = 1.sp
            )
            Text(
                text = "Nintendo Switch NRO loader",
                fontSize = 11.sp,
                color = NxTextSecondary,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// ─── Card wrapper ─────────────────────────────────────────────────────────────
@Composable
fun NxCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NxCard)
            .border(1.dp, NxBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        content = content
    )
}

// ─── Section label ────────────────────────────────────────────────────────────
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = NxTextSecondary,
        letterSpacing = 1.5.sp
    )
}

// ─── Spinning loader icon ─────────────────────────────────────────────────────
@Composable
fun SpinnerIcon() {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing)
        ),
        label = "rotation"
    )
    Text(
        text = "◌",
        color = Color.White,
        fontSize = 16.sp,
        modifier = Modifier.rotate(rotation)
    )
}

// ─── Status panel (NRO) ───────────────────────────────────────────────────────
@Composable
fun StatusPanel(state: TransferState) {
    val (bg, border, textColor, icon, text) = when (state) {
        is TransferState.Progress -> StatusStyle(
            bg = Color(0xFF0D1F17),
            border = Color(0xFF1A4D2E),
            text = NxTextPrimary,
            icon = "↑",
            label = buildString {
                if (state.totalBytes > 0) {
                    val pct = (state.bytesSent * 100 / state.totalBytes).toInt()
                    append("Sending… $pct%  (${state.bytesSent.toKb()} / ${state.totalBytes.toKb()})")
                } else {
                    append("Sending… ${state.bytesSent.toKb()} sent")
                }
            }
        )

        is TransferState.Success -> StatusStyle(
            bg = Color(0xFF0D1F17),
            border = NxGreen.copy(alpha = 0.4f),
            text = NxGreen,
            icon = "✓",
            label = "Transfer complete — ${state.bytesSent.toKb()} sent"
        )

        is TransferState.Error -> StatusStyle(
            bg = Color(0xFF1F0D0D),
            border = NxRed.copy(alpha = 0.4f),
            text = Color(0xFFFF6B6B),
            icon = "✕",
            label = state.message
        )

        TransferState.Idle -> StatusStyle(
            Color.Transparent,
            Color.Transparent,
            Color.Transparent,
            "",
            ""
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(icon, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text, color = textColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }

    if (state is TransferState.Progress && state.totalBytes > 0) {
        Spacer(Modifier.height((-12).dp))
        LinearProgressIndicator(
            progress = { state.bytesSent.toFloat() / state.totalBytes },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)),
            color = NxGreen,
            trackColor = NxBorder
        )
    }
}

// ─── Status panel (Games) ─────────────────────────────────────────────────────
@Composable
fun StatusPanelForGame(state: GameTransferState) {
    val style = getStatusStyleForGame(state)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(style.bg)
            .border(1.dp, style.border, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(style.icon, color = style.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(style.label, color = style.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }

    // Show progress bar only for Transferring state
    if (state is GameTransferState.Transferring && state.progress.totalBytes > 0) {
        Spacer(Modifier.height((-12).dp))
        LinearProgressIndicator(
            progress = { state.progress.bytesTransferred.toFloat() / state.progress.totalBytes },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)),
            color = NxGreen,
            trackColor = NxBorder
        )
    }
}

private data class StatusStyle(
    val bg: Color, val border: Color, val text: Color, val icon: String, val label: String
)

// ─── nxlink send logic ────────────────────────────────────────────────────────
/**
 * Real nx-hbmenu netloader protocol (TCP port 28280), from netloader.c:
 *
 *  CLIENT sends:
 *    [0..3]   namelen  — int32, length of the filepath string
 *    [4..N]   filepath — bare filename bytes (e.g. "app.nro"), no null terminator
 *    [N..N+3] filelen  — int32, uncompressed file size in bytes
 *    then: zlib-deflated data sent as chunks:
 *      each chunk: [4-byte chunksize LE][chunksize bytes of deflated data]
 *
 *  Switch responds with int32 (0 = ok) after receiving header.
 *  After all data, Switch sends int32 response again.
 *  Client then sends int32 cmdlen + cmdlen bytes of args.
 */
private suspend fun sendNro(
    context: Context,
    ip: String,
    uri: Uri,
    onStateChange: (TransferState) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val cr = context.contentResolver
        val fileSize = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

        // Bare filename only, no path components
        val rawName = (uri.getFileName(context) ?: "app.nro")
            .substringAfterLast("/")
            .substringAfterLast("\\")
            .take(64)
        val fileNameBytes = rawName.toByteArray(Charsets.UTF_8)

        onStateChange(TransferState.Progress(0, fileSize))

        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(ip, NXLINK_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 10_000

            val out = java.io.DataOutputStream(socket.getOutputStream())
            val inp = java.io.DataInputStream(socket.getInputStream())

            // 1. Send namelen (int32 native — Switch is little-endian, but Java
            //    DataOutputStream is big-endian; use writeIntLE helper)
            writeInt32LE(out, fileNameBytes.size)

            // 2. Send filepath bytes
            out.write(fileNameBytes)

            // 3. Send filelen (int32 LE)
            writeInt32LE(out, fileSize.toInt())
            out.flush()

            // 4. Read Switch response (int32); 0 = ok
            val response = readInt32LE(inp)
            if (response != 0) {
                throw IOException("Switch rejected transfer, response=$response")
            }

            // 5. Send zlib-compressed file data in chunks
            val ZLIB_CHUNK = 16 * 1024
            cr.openInputStream(uri)?.use { input ->
                val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION)
                val inBuf = ByteArray(ZLIB_CHUNK)
                val outBuf = ByteArray(ZLIB_CHUNK)
                var sent = 0L
                var read: Int

                while (input.read(inBuf).also { read = it } != -1) {
                    deflater.setInput(inBuf, 0, read)
                    while (!deflater.needsInput()) {
                        val compressed = deflater.deflate(
                            outBuf, 0, outBuf.size,
                            java.util.zip.Deflater.SYNC_FLUSH
                        )
                        if (compressed > 0) {
                            writeInt32LE(out, compressed)
                            out.write(outBuf, 0, compressed)
                        }
                    }
                    sent += read
                    onStateChange(TransferState.Progress(sent, fileSize))
                }

                // Flush remaining deflater output
                if (!deflater.finished()) {
                    deflater.finish()
                    while (!deflater.finished()) {
                        val compressed = deflater.deflate(outBuf)
                        if (compressed > 0) {
                            writeInt32LE(out, compressed)
                            out.write(outBuf, 0, compressed)
                        }
                    }
                }
                deflater.end()
            } ?: throw IOException("Cannot open file stream")

            out.flush()

            // 6. Read final response from Switch
            val finalResponse = readInt32LE(inp)
            if (finalResponse != 0) {
                throw IOException("Switch reported error after transfer: $finalResponse")
            }

            // 7. Send cmdlen = 0 (no args)
            writeInt32LE(out, 0)
            out.flush()

            onStateChange(TransferState.Success(fileSize))
        }
    } catch (e: SocketTimeoutException) {
        onStateChange(TransferState.Error("Connection timed out — is the Switch netloader active? (Press Y in hbmenu)"))
    } catch (e: IOException) {
        onStateChange(TransferState.Error("Network error: ${e.message}"))
    } catch (e: Exception) {
        onStateChange(TransferState.Error("Unexpected error: ${e.message}"))
    }
}

// ─── Little-endian int32 helpers (Switch is LE, Java DataStream is BE) ────────
private fun writeInt32LE(out: java.io.OutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
    out.write((value shr 16) and 0xFF)
    out.write((value shr 24) and 0xFF)
}

private fun readInt32LE(inp: java.io.InputStream): Int {
    val b0 = inp.read()
    val b1 = inp.read()
    val b2 = inp.read()
    val b3 = inp.read()
    if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw IOException("Connection closed while reading response")
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
private fun Uri.getFileName(context: Context): String? {
    context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx != -1) return cursor.getString(idx)
        }
    }
    return null
}

private fun Long.toKb(): String = when {
    this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024 -> "%.1f KB".format(this / 1_024.0)
    else -> "$this B"
}

private fun String.isValidIpv4(): Boolean {
    val parts = split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
