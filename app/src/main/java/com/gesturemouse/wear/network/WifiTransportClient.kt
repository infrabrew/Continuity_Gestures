package com.gesturemouse.wear.network

import android.util.Base64
import android.util.Log
import com.gesturemouse.wear.mode.HidAction
import com.gesturemouse.wear.model.CrownAction
import com.gesturemouse.wear.model.InputMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-specific quick macro button pushed from a paired Wi-Fi companion daemon
 * (e.g., Mute, Undo, Next Tab, Play/Pause) for one-tap execution from the watch.
 */
data class HostMacro(
    val id: String,
    val label: String,
    val appName: String = ""
)

/**
 * Active-App Auto-Profile pushed by the host companion daemon whenever the focused window
 * changes (browser, media player, IDE/terminal, or presentation).
 */
data class HostAppProfile(
    val appName: String,
    val category: String,
    val recommendedCrown: CrownAction? = null,
    val recommendedMode: InputMode? = null,
    val macros: List<HostMacro> = emptyList()
)

/**
 * Clipboard snippet or URL received from the host computer for Quick Host Handoff.
 */
data class HostHandoffMessage(
    val kind: String, // "url" or "clipboard"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Pure-Kotlin binary UDP packet encoder and two-way JSON protocol parser for GestureMouse's
 * 5 GHz Wi-Fi 6 low-latency transport, Active-App Auto-Profiles, Multi-Monitor Teleport, and
 * Quick Host Handoff.
 */
object WifiProtocolCodec {
    const val MAGIC_0: Byte = 0x47 // 'G'
    const val MAGIC_1: Byte = 0x4D // 'M'
    const val PACKET_SIZE = 12

    const val TYPE_MOUSE: Byte = 1
    const val TYPE_KEY_TAP: Byte = 2
    const val TYPE_CONSUMER_TAP: Byte = 3
    const val TYPE_MACRO: Byte = 4
    const val TYPE_TELEPORT: Byte = 5

    fun encodeAction(action: HidAction, seq: Int): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.putShort((seq and 0xFFFF).toShort())
        when (action) {
            is HidAction.MouseState -> {
                buf.put(TYPE_MOUSE)
                buf.put((action.buttons and 0xFF).toByte())
                buf.put(action.dx.coerceIn(-127, 127).toByte())
                buf.put(action.dy.coerceIn(-127, 127).toByte())
                buf.put(action.wheel.coerceIn(-127, 127).toByte())
                buf.put(action.pan.coerceIn(-127, 127).toByte())
            }
            is HidAction.MouseClick -> {
                buf.put(TYPE_MOUSE)
                buf.put(((action.buttonMask and 0x7F) or 0x80).toByte())
                buf.put(0); buf.put(0); buf.put(0); buf.put(0)
            }
            is HidAction.KeyTap -> {
                buf.put(TYPE_KEY_TAP)
                buf.put(action.modifiers)
                buf.put(action.keyCode)
                buf.put(0); buf.put(0); buf.put(0)
            }
            is HidAction.ConsumerTap -> {
                buf.put(TYPE_CONSUMER_TAP)
                buf.put(((action.bitmask ushr 8) and 0xFF).toByte())
                buf.put((action.bitmask and 0xFF).toByte())
                buf.put(0); buf.put(0); buf.put(0)
            }
        }
        buf.put(0)
        return finalizeWithXor(buf.array())
    }

    /**
     * Encodes an instant Multi-Monitor Teleport UDP packet instructing a companion daemon to
     * warp the OS cursor directly to the center of display [monitorIndex] (1..[monitorCount]).
     */
    fun encodeTeleport(monitorIndex: Int, monitorCount: Int, seq: Int): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.putShort((seq and 0xFFFF).toShort())
        buf.put(TYPE_TELEPORT)
        buf.put(monitorIndex.coerceIn(1, 3).toByte())
        buf.put(monitorCount.coerceIn(1, 3).toByte())
        buf.put(0); buf.put(0); buf.put(0); buf.put(0)
        return finalizeWithXor(buf.array())
    }

    /**
     * Parses a lightweight JSON macro sync payload from the companion WebSocket server:
     * `{"app":"Figma","macros":[{"id":"undo","label":"Undo"},{"id":"zoom","label":"Zoom 100%"}]}`
     */
    fun parseMacroSyncJson(json: String): List<HostMacro> {
        val appMatch = APP_NAME_REGEX.find(json)?.groupValues?.getOrNull(1).orEmpty()
        val result = mutableListOf<HostMacro>()
        MACRO_ENTRY_REGEX.findAll(json).forEach { match ->
            val id = match.groupValues[1].trim()
            val label = match.groupValues[2].trim()
            if (id.isNotEmpty() && label.isNotEmpty()) {
                result.add(HostMacro(id = id, label = label, appName = appMatch))
            }
        }
        return result.take(6)
    }

    /**
     * Parses an Active-App Auto-Profile payload pushed by the host daemon:
     * `{"type":"profile","app":"Spotify","category":"media","crown":"VOLUME","mode":"MEDIA_CONTROLS","macros":[...]}`
     */
    fun parseAppProfileJson(json: String): HostAppProfile? {
        val type = TYPE_REGEX.find(json)?.groupValues?.getOrNull(1)
        val app = APP_NAME_REGEX.find(json)?.groupValues?.getOrNull(1).orEmpty()
        if (type != "profile" && app.isBlank()) return null

        val category = CATEGORY_REGEX.find(json)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
        val explicitCrown = CROWN_REGEX.find(json)?.groupValues?.getOrNull(1)
            ?.let { runCatching { CrownAction.valueOf(it) }.getOrNull() }
        val explicitMode = MODE_REGEX.find(json)?.groupValues?.getOrNull(1)
            ?.let { runCatching { InputMode.valueOf(it) }.getOrNull() }

        val inferredCrown = explicitCrown ?: when (category) {
            "media", "music", "video" -> CrownAction.VOLUME
            "browser", "docs", "reader" -> CrownAction.SMOOTH_SCROLL
            "ide", "editor", "timeline" -> CrownAction.TIMELINE_SCRUB
            else -> null
        }
        val inferredMode = explicitMode ?: when (category) {
            "presenter", "slides" -> InputMode.PRESENTER
            else -> null
        }
        val macros = parseMacroSyncJson(json).ifEmpty { defaultMacrosForCategory(category, app) }
        return HostAppProfile(
            appName = app.ifBlank { category.replaceFirstChar { it.uppercase() } },
            category = category.ifBlank { "general" },
            recommendedCrown = inferredCrown,
            recommendedMode = inferredMode,
            macros = macros
        )
    }

    /**
     * Parses a Quick Host Handoff JSON message containing a URL or clipboard snippet:
     * `{"type":"handoff","kind":"url","text":"https://developer.android.com"}`
     */
    fun parseHandoffJson(json: String): HostHandoffMessage? {
        val type = TYPE_REGEX.find(json)?.groupValues?.getOrNull(1)
        if (type != "handoff") return null
        val kind = KIND_REGEX.find(json)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "clipboard" }
        val text = TEXT_REGEX.find(json)?.groupValues?.getOrNull(1).orEmpty().trim()
        if (text.isEmpty()) return null
        return HostHandoffMessage(kind = kind, text = text)
    }

    fun encodeMacroTriggerJson(macroId: String): String {
        val safeId = sanitizeJson(macroId)
        return """{"type":"macro","id":"$safeId"}"""
    }

    fun encodeHandoffPushJson(text: String, kind: String = "clipboard"): String {
        val safeKind = sanitizeJson(kind)
        val safeText = sanitizeJson(text)
        return """{"type":"handoff_push","kind":"$safeKind","text":"$safeText"}"""
    }

    fun encodeRequestHandoffJson(): String = """{"type":"handoff_pull"}"""

    private fun defaultMacrosForCategory(category: String, app: String): List<HostMacro> = when (category) {
        "browser" -> listOf(
            HostMacro("browser_back", "Back", app),
            HostMacro("browser_next_tab", "Next Tab", app),
            HostMacro("browser_new_tab", "New Tab", app)
        )
        "media", "music", "video" -> listOf(
            HostMacro("media_play", "Play/Pause", app),
            HostMacro("media_next", "Next", app),
            HostMacro("media_mute", "Mute", app)
        )
        "ide", "terminal" -> listOf(
            HostMacro("ide_run", "Run", app),
            HostMacro("ide_undo", "Undo", app),
            HostMacro("ide_search", "Find", app)
        )
        else -> emptyList()
    }

    private fun finalizeWithXor(arr: ByteArray): ByteArray {
        var xor = 0
        for (i in 0 until PACKET_SIZE - 1) xor = xor xor (arr[i].toInt() and 0xFF)
        arr[PACKET_SIZE - 1] = xor.toByte()
        return arr
    }

    private fun sanitizeJson(input: String): String =
        input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private val TYPE_REGEX = Regex(""""type"\s*:\s*"([^"]*)"""")
    private val APP_NAME_REGEX = Regex(""""app"\s*:\s*"([^"]*)"""")
    private val CATEGORY_REGEX = Regex(""""category"\s*:\s*"([^"]*)"""")
    private val CROWN_REGEX = Regex(""""crown"\s*:\s*"([^"]*)"""")
    private val MODE_REGEX = Regex(""""mode"\s*:\s*"([^"]*)"""")
    private val KIND_REGEX = Regex(""""kind"\s*:\s*"([^"]*)"""")
    private val TEXT_REGEX = Regex(""""text"\s*:\s*"([^"]*)"""")
    private val MACRO_ENTRY_REGEX = Regex(
        """\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"\s*\}"""
    )
}

/**
 * Dual 5 GHz Wi-Fi 6 transport pairing an ultra-low-latency UDP datagram emitter with a
 * lightweight RFC 6455 WebSocket two-way channel for Active-App Auto-Profiles, macro buttons,
 * and Quick Host Handoff (URL/clipboard sync).
 */
class WifiTransportClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seqCounter = AtomicInteger(0)

    @Volatile
    private var targetHost: String = DEFAULT_HOST_IP

    @Volatile
    private var udpPort: Int = DEFAULT_UDP_PORT

    @Volatile
    private var targetAddress: InetAddress? = null

    @Volatile
    private var isEnabled: Boolean = false

    private var udpSocket: DatagramSocket? = null
    private var wsSocket: Socket? = null
    private var wsOut: OutputStream? = null
    private var wsJob: Job? = null

    private val _isWsConnected = MutableStateFlow(false)
    val isWsConnected: StateFlow<Boolean> = _isWsConnected.asStateFlow()

    private val _syncedMacros = MutableStateFlow<List<HostMacro>>(emptyList())
    val syncedMacros: StateFlow<List<HostMacro>> = _syncedMacros.asStateFlow()

    private val _activeAppProfile = MutableStateFlow<HostAppProfile?>(null)
    val activeAppProfile: StateFlow<HostAppProfile?> = _activeAppProfile.asStateFlow()

    private val _receivedHandoff = MutableStateFlow<HostHandoffMessage?>(null)
    val receivedHandoff: StateFlow<HostHandoffMessage?> = _receivedHandoff.asStateFlow()

    fun configure(enabled: Boolean, hostIp: String, port: Int = DEFAULT_UDP_PORT) {
        val hostChanged = targetHost != hostIp || udpPort != port
        targetHost = hostIp.trim().ifBlank { DEFAULT_HOST_IP }
        udpPort = port.coerceIn(1024, 65530)

        if (!enabled) {
            stop()
            return
        }
        if (!isEnabled || hostChanged) {
            start()
        }
    }

    private fun start() {
        stop()
        isEnabled = true
        scope.launch {
            runCatching {
                targetAddress = InetAddress.getByName(targetHost)
                udpSocket = DatagramSocket()
            }.onFailure {
                Log.w(TAG, "Unable to initialize Wi-Fi UDP socket for $targetHost:$udpPort", it)
            }
        }
        startWebSocketSyncLoop()
    }

    fun stop() {
        isEnabled = false
        wsJob?.cancel()
        wsJob = null
        _isWsConnected.value = false
        _syncedMacros.value = emptyList()
        _activeAppProfile.value = null
        runCatching { wsSocket?.close() }
        wsSocket = null
        wsOut = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        targetAddress = null
    }

    fun sendAction(action: HidAction) {
        if (!isEnabled) return
        sendUdpPayload(WifiProtocolCodec.encodeAction(action, seqCounter.getAndIncrement()))
    }

    fun sendTeleport(monitorIndex: Int, monitorCount: Int) {
        if (!isEnabled) return
        sendUdpPayload(WifiProtocolCodec.encodeTeleport(monitorIndex, monitorCount, seqCounter.getAndIncrement()))
    }

    fun triggerMacro(macroId: String) {
        if (!isEnabled) return
        sendWebSocketJson(WifiProtocolCodec.encodeMacroTriggerJson(macroId))
    }

    fun pushHandoffToHost(text: String, kind: String = "clipboard") {
        if (!isEnabled || text.isBlank()) return
        sendWebSocketJson(WifiProtocolCodec.encodeHandoffPushJson(text, kind))
    }

    fun requestHandoffFromHost() {
        if (!isEnabled) return
        sendWebSocketJson(WifiProtocolCodec.encodeRequestHandoffJson())
    }

    private fun sendUdpPayload(payload: ByteArray) {
        scope.launch {
            val socket = udpSocket ?: return@launch
            val addr = targetAddress ?: runCatching { InetAddress.getByName(targetHost) }
                .getOrNull()
                ?.also { targetAddress = it }
                ?: return@launch
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, addr, udpPort))
            }
        }
    }

    private fun sendWebSocketJson(json: String) {
        scope.launch {
            val out = wsOut ?: return@launch
            runCatching {
                writeWebSocketTextFrame(out, json)
            }.onFailure {
                Log.w(TAG, "Failed sending Wi-Fi WebSocket message", it)
            }
        }
    }

    private fun startWebSocketSyncLoop() {
        val wsPort = udpPort + 1
        wsJob = scope.launch {
            while (isActive && isEnabled) {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(targetHost, wsPort), 2_500)
                    socket.soTimeout = 15_000

                    val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    val secKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                    val out = socket.getOutputStream()
                    val handshake = buildString {
                        append("GET /gesturemouse HTTP/1.1\r\n")
                        append("Host: $targetHost:$wsPort\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $secKey\r\n")
                        append("Sec-WebSocket-Version: 13\r\n\r\n")
                    }
                    out.write(handshake.toByteArray(Charsets.UTF_8))
                    out.flush()

                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val statusLine = readHttpLine(input)
                    if (!statusLine.contains("101")) {
                        throw IllegalStateException("WebSocket upgrade rejected: $statusLine")
                    }
                    while (true) {
                        val header = readHttpLine(input)
                        if (header.isEmpty()) break
                    }

                    wsSocket = socket
                    wsOut = out
                    _isWsConnected.value = true

                    while (isActive && isEnabled) {
                        val b0 = input.read()
                        if (b0 == -1) break
                        val opcode = b0 and 0x0F
                        val b1 = input.read()
                        if (b1 == -1) break
                        val masked = (b1 and 0x80) != 0
                        var length = (b1 and 0x7F).toLong()
                        if (length == 126L) {
                            length = input.readUnsignedShort().toLong()
                        } else if (length == 127L) {
                            length = input.readLong()
                        }
                        if (length < 0 || length > 32_768L) break

                        val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
                        val frameData = ByteArray(length.toInt())
                        input.readFully(frameData)
                        if (maskKey != null) {
                            for (i in frameData.indices) {
                                frameData[i] = (frameData[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                            }
                        }

                        when (opcode) {
                            0x1 -> handleIncomingTextFrame(frameData.toString(Charsets.UTF_8))
                            0x8 -> break
                            0x9 -> writeWebSocketPong(out, frameData)
                        }
                    }
                } catch (_: Exception) {
                    // Host companion offline or unreachable — retry gently
                } finally {
                    _isWsConnected.value = false
                    wsOut = null
                    runCatching { socket?.close() }
                    if (wsSocket === socket) wsSocket = null
                }
                delay(4_000L)
            }
        }
    }

    private fun handleIncomingTextFrame(text: String) {
        val handoff = WifiProtocolCodec.parseHandoffJson(text)
        if (handoff != null) {
            _receivedHandoff.value = handoff
            return
        }

        val profile = WifiProtocolCodec.parseAppProfileJson(text)
        if (profile != null) {
            _activeAppProfile.value = profile
            if (profile.macros.isNotEmpty()) {
                _syncedMacros.value = profile.macros
            }
            return
        }

        val macros = WifiProtocolCodec.parseMacroSyncJson(text)
        if (macros.isNotEmpty()) {
            _syncedMacros.value = macros
        }
    }

    private fun readHttpLine(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch == -1 || ch == '\n'.code) break
            if (ch != '\r'.code) sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private fun writeWebSocketTextFrame(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(if (payload.size < 126) 6 else 8)
        header.put(0x81.toByte())
        if (payload.size < 126) {
            header.put((0x80 or payload.size).toByte())
        } else {
            header.put((0x80 or 126).toByte())
            header.putShort(payload.size.toShort())
        }
        header.put(mask)
        val maskedPayload = ByteArray(payload.size) { i ->
            (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        synchronized(out) {
            out.write(header.array())
            out.write(maskedPayload)
            out.flush()
        }
    }

    private fun writeWebSocketPong(out: OutputStream, payload: ByteArray) {
        val safeLen = payload.size.coerceAtMost(125)
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val frame = ByteArray(2 + 4 + safeLen)
        frame[0] = 0x8A.toByte()
        frame[1] = (0x80 or safeLen).toByte()
        System.arraycopy(mask, 0, frame, 2, 4)
        for (i in 0 until safeLen) {
            frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        synchronized(out) {
            out.write(frame)
            out.flush()
        }
    }

    companion object {
        private const val TAG = "WifiTransportClient"
        const val DEFAULT_HOST_IP = "192.168.1.100"
        const val DEFAULT_UDP_PORT = 5959
    }
}
