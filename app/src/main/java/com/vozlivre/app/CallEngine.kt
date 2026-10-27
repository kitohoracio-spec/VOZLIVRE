package com.vozlivre.app

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Máquina de estados da chamada, independente do transporte.
 *
 * Mensagens de controlo (texto, separadas por "|"):
 *   CALL|callId|nome    quem liga repete até haver resposta
 *   RINGING|callId      o outro lado está a tocar
 *   ACCEPT|callId       atendeu (repetido sempre que chega um CALL duplicado)
 *   REJECT|callId  BUSY|callId  HANGUP|callId
 *   PING|callId         batimento de 2 em 2 s durante a chamada
 *
 * Todas as transições correm na thread principal.
 */
class CallEngine(private val app: Context) : TransportListener {

    enum class State { IDLE, OUTGOING, INCOMING, ACTIVE }

    interface Ui {
        fun onStateChanged()
        fun onPeersChanged()
        fun onLog()
        fun onToast(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("vozlivre", Context.MODE_PRIVATE)
    val myId: String = prefs.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { prefs.edit().putString("id", it).apply() }

    @Volatile var ui: Ui? = null
    var displayName: String = prefs.getString("name", "Utilizador") ?: "Utilizador"
        private set

    val lan = LanTransport(app, myId) { displayName }
    val bt = BluetoothTransport(app)
    val p2p = WifiDirect(app, lan, { log(it) }, { ui?.onPeersChanged() })

    @Volatile var state = State.IDLE; private set
    @Volatile var peer: Peer? = null; private set
    var statusText = "Nenhuma chamada ativa"; private set
    var muted = false; private set
    var speaker = false; private set
    var activeSince = 0L; private set

    private var callId = ""
    private var ringing = false
    private var outgoingStart = 0L
    private var lastCallSeen = 0L
    @Volatile private var lastRemote = 0L
    private var audio: AudioEngine? = null
    private var seq = 0
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    private val logLines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        lan.listener = this
        bt.listener = this
    }

    // ---------------------------------------------------------------- ciclo de vida

    fun start() {
        if (started) return
        started = true
        lan.start()
        p2p.start()
        log("Motor iniciado. O meu id: $myId")
    }

    fun startBluetooth() { bt.startServer() }

    fun stopAll() {
        if (!started) return
        started = false
        p2p.stop(); lan.stop(); bt.stop()
        log("Motor parado")
    }

    fun setName(n: String) {
        displayName = n.trim().ifBlank { "Utilizador" }
        prefs.edit().putString("name", displayName).apply()
        lan.wake()
    }

    fun transportFor(peerId: String): Transport = if (peerId.startsWith("bt:")) bt else lan

    fun allPeers(): List<Peer> = lan.peers() + bt.peers()

    // ---------------------------------------------------------------- diagnóstico

    fun log(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
        }
        main.post { ui?.onLog() }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    // ---------------------------------------------------------------- ações do utilizador

    fun startCall(p: Peer) {
        if (state != State.IDLE) { ui?.onToast("Já estás numa chamada"); return }
        val id = newCallId()
        state = State.OUTGOING; peer = p; callId = id; ringing = false
        statusText = "A ligar a ${p.name}..."
        outgoingStart = SystemClock.elapsedRealtime()
        log("Chamada $id para ${p.name} (${p.id}) via ${p.via.ifBlank { "bluetooth" }}")
        ui?.onStateChanged()
        transportFor(p.id).connect(p.id) { ok, err ->
            main.post {
                if (state != State.OUTGOING || callId != id) return@post
                if (!ok) finish("Não foi possível ligar: ${err ?: "sem resposta"}", false)
                else callTick.run()
            }
        }
    }

    fun answer() {
        val p = peer ?: return
        if (state != State.INCOMING) return
        send(p, "ACCEPT|$callId")
        becomeActive()
    }

    fun reject() {
        val p = peer ?: return
        if (state != State.INCOMING) return
        send(p, "REJECT|$callId")
        finish("Chamada recusada", false)
    }

    fun hangup() {
        if (state == State.IDLE) return
        finish("Chamada terminada", true)
    }

    fun toggleMute() {
        muted = !muted
        audio?.muted = muted
        ui?.onStateChanged()
    }

    fun toggleSpeaker() {
        speaker = !speaker
        audio?.setSpeaker(speaker)
        ui?.onStateChanged()
    }

    // ---------------------------------------------------------------- TransportListener (threads de rede)

    override fun onPeersChanged() { main.post { ui?.onPeersChanged() } }

    override fun onLog(msg: String) { log(msg) }

    override fun onControl(peerId: String, text: String) { main.post { handleControl(peerId, text) } }

    override fun onAudio(peerId: String, seq: Int, pcm: ByteArray) {
        if (state == State.ACTIVE && peerId == peer?.id) {
            lastRemote = SystemClock.elapsedRealtime()
            audio?.onRemoteFrame(seq, pcm)
        }
    }

    override fun onPeerDisconnected(peerId: String) {
        main.post {
            if (state != State.IDLE && peer?.id == peerId) finish("Ligação perdida", false)
        }
    }

    // ---------------------------------------------------------------- protocolo

    private fun handleControl(peerId: String, text: String) {
        val parts = text.split("|")
        if (parts.size < 2) return
        val cmd = parts[0]
        val cid = parts[1]
        val cur = peer
        val sameCall = cid == callId && cur != null && cur.id == peerId
        when (cmd) {
            "CALL" -> {
                val name = parts.getOrNull(2)?.replace('_', ' ') ?: "Desconhecido"
                val p = allPeers().firstOrNull { it.id == peerId }
                    ?: Peer(peerId, name, if (peerId.startsWith("bt:")) PeerKind.BLUETOOTH else PeerKind.LAN)
                if (state == State.IDLE) {
                    state = State.INCOMING; peer = p; callId = cid
                    statusText = "Chamada de ${p.name}"
                    lastCallSeen = SystemClock.elapsedRealtime()
                    log("Chamada recebida de ${p.name}")
                    send(p, "RINGING|$cid")
                    startRinging()
                    main.removeCallbacks(incomingWatch)
                    main.postDelayed(incomingWatch, 1000)
                    ui?.onStateChanged()
                } else if (sameCall) {
                    lastCallSeen = SystemClock.elapsedRealtime()
                    if (state == State.INCOMING) send(p, "RINGING|$cid")
                    else if (state == State.ACTIVE) send(p, "ACCEPT|$cid")
                } else {
                    send(p, "BUSY|$cid")
                }
            }
            "RINGING" -> if (state == State.OUTGOING && sameCall && !ringing) {
                ringing = true
                statusText = "A tocar em ${cur?.name}..."
                ui?.onStateChanged()
            }
            "ACCEPT" -> if (state == State.OUTGOING && sameCall) becomeActive()
            "REJECT" -> if (state == State.OUTGOING && sameCall) finish("Chamada recusada", false)
            "BUSY" -> if (state == State.OUTGOING && sameCall) finish("O outro telemóvel está ocupado", false)
            "HANGUP" -> if (state != State.IDLE && sameCall) finish("A outra pessoa terminou a chamada", false)
            "PING" -> if (state == State.ACTIVE && sameCall) lastRemote = SystemClock.elapsedRealtime()
        }
    }

    private fun send(p: Peer, text: String) { transportFor(p.id).sendControl(p.id, text) }

    private val callTick = object : Runnable {
        override fun run() {
            if (state != State.OUTGOING) return
            val p = peer ?: return
            if (SystemClock.elapsedRealtime() - outgoingStart > 40_000) { finish("Sem resposta", true); return }
            send(p, "CALL|$callId|${safe(displayName)}")
            main.postDelayed(this, if (ringing) 2000 else 1000)
        }
    }

    private val incomingWatch = object : Runnable {
        override fun run() {
            if (state != State.INCOMING) return
            if (SystemClock.elapsedRealtime() - lastCallSeen > 6000) { finish("Chamada perdida", false); return }
            main.postDelayed(this, 1000)
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (state != State.ACTIVE) return
            val p = peer ?: return
            send(p, "PING|$callId")
            if (SystemClock.elapsedRealtime() - lastRemote > 9000) { finish("Ligação perdida", true); return }
            main.postDelayed(this, 2000)
        }
    }

    private fun becomeActive() {
        val p = peer ?: return
        main.removeCallbacks(callTick)
        main.removeCallbacks(incomingWatch)
        stopRinging()
        state = State.ACTIVE
        activeSince = SystemClock.elapsedRealtime()
        lastRemote = activeSince
        statusText = "Em chamada com ${p.name}"
        log("Chamada ativa com ${p.name}")
        try { CallService.start(app, "Em chamada com ${p.name}") } catch (e: Exception) { log("Serviço de chamada não arrancou: ${e.message}") }
        acquireLocks()
        val t = transportFor(p.id)
        seq = 0
        val a = AudioEngine(app, t.audioRate) { buf, n ->
            seq = (seq + 1) and 0xFFFF
            t.sendAudio(p.id, seq, buf, n)
        }
        a.muted = muted
        val err = a.start()
        if (err != null) { log(err); ui?.onToast(err); finish(err, true); return }
        a.setSpeaker(speaker)
        audio = a
        main.removeCallbacks(keepAlive)
        main.postDelayed(keepAlive, 2000)
        ui?.onStateChanged()
    }

    private fun finish(message: String, notify: Boolean) {
        val p = peer
        val cid = callId
        if (notify && p != null && state != State.IDLE) {
            send(p, "HANGUP|$cid")
            main.postDelayed({ send(p, "HANGUP|$cid") }, 200)
        }
        main.removeCallbacks(callTick)
        main.removeCallbacks(incomingWatch)
        main.removeCallbacks(keepAlive)
        stopRinging()
        audio?.stop(); audio = null
        releaseLocks()
        try { CallService.stop(app) } catch (_: Exception) {}
        log("Chamada terminada: $message")
        state = State.IDLE; peer = null; callId = ""; ringing = false
        muted = false; speaker = false
        statusText = message
        ui?.onToast(message)
        ui?.onStateChanged()
    }

    // ---------------------------------------------------------------- toque e bloqueios

    private fun startRinging() {
        try {
            val r = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            r?.play(); ringtone = r
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
        } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try {
            @Suppress("DEPRECATION")
            (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        } catch (_: Exception) {}
    }

    private fun acquireLocks() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vozlivre:call").apply { setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
        try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "vozlivre:call").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wifiLock = null
    }

    private fun newCallId() = UUID.randomUUID().toString().take(6)
}
