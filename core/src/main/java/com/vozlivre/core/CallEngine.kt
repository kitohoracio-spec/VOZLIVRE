package com.vozlivre.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
class CallEngine(private val app: Context, val role: String) : TransportListener {

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

    /** "server" ou "client", fixado pelo APK. */
    val mode: String = role

    val lan = LanTransport(app, myId, { displayName }, { if (mode == "server") "S" else if (mode == "client") "C" else "" })
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

    var listenInBackground: Boolean = prefs.getBoolean("listen", false)
        private set
    private var lastDecryptLog = 0L

    val usingDefaultPassword: Boolean get() = prefs.getString("pw", "").isNullOrBlank()

    init {
        lan.listener = this
        bt.listener = this
        try { Secure.setPassword(prefs.getString("pw", "") ?: "") } catch (e: Exception) { log("Chave não iniciada: ${e.message}") }
    }

    // ---------------------------------------------------------------- ciclo de vida

    fun start() {
        if (started) return
        started = true
        lan.start()
        p2p.start()
        sessionStart = SystemClock.elapsedRealtime()
        log("Motor iniciado. O meu id: $myId")
        if (listenInBackground) startListenService()
    }

    fun setListen(on: Boolean) {
        listenInBackground = on
        prefs.edit().putBoolean("listen", on).apply()
        if (on) startListenService() else try { ListenService.stop(app) } catch (_: Exception) {}
    }

    private fun startListenService() {
        try { ListenService.start(app) } catch (e: Exception) { log("Serviço de escuta não arrancou: ${e.message}") }
    }

    fun setPassword(pw: String) {
        prefs.edit().putString("pw", pw.trim()).apply()
        try { Secure.setPassword(pw.trim()) } catch (e: Exception) { log("Chave não iniciada: ${e.message}") }
        log(if (pw.isBlank()) "Senha padrão ativa" else "Senha das chamadas atualizada")
    }

    fun startBluetooth() { bt.startServer() }

    fun stopAll() {
        if (!started) return
        started = false
        p2p.stop(); lan.stop(); bt.stop()
        try { ListenService.stop(app) } catch (_: Exception) {}
        log("Motor parado")
    }

    // ---------------------------------------------------------------- clientes bloqueados e servidor escolhido

    private val blocked: MutableSet<String> = HashSet(prefs.getStringSet("blocked", emptySet()) ?: emptySet())

    fun isBlocked(id: String) = blocked.contains(id)

    fun clearBlocked() {
        blocked.clear()
        prefs.edit().remove("blocked").apply()
        ui?.onPeersChanged()
    }

    fun selectedServerPeer(): Peer? = serverId?.let { id -> allPeers().firstOrNull { it.id == id } }

    fun setBlocked(id: String, on: Boolean) {
        if (on) blocked.add(id) else blocked.remove(id)
        prefs.edit().putStringSet("blocked", HashSet(blocked)).apply()
        log(if (on) "Bloqueado: $id" else "Desbloqueado: $id")
        ui?.onPeersChanged()
    }

    var serverId: String? = prefs.getString("server_id", null)
        private set

    fun selectServer(id: String?) {
        serverId = id
        prefs.edit().apply { if (id == null) remove("server_id") else putString("server_id", id) }.apply()
        ui?.onPeersChanged()
    }

    var defaultSpeaker: Boolean = prefs.getBoolean("default_speaker", false)
        private set

    fun setDefaultSpeaker(on: Boolean) {
        defaultSpeaker = on
        prefs.edit().putBoolean("default_speaker", on).apply()
    }

    // ---------------------------------------------------------------- histórico

    class CallRecord(val peerId: String, val peerName: String, val incoming: Boolean, val at: Long, val seconds: Int, val result: String)

    private val historyList = ArrayList<CallRecord>()
    val history: List<CallRecord> get() = historyList.toList()
    private var incomingCall = false

    // ---------------------------------------------------------------- aprovação de clientes, favoritos e medições

    private val approved: MutableSet<String> = HashSet(prefs.getStringSet("approved", emptySet()) ?: emptySet())

    var approvalRequired: Boolean = prefs.getBoolean("approval", false)
        private set

    fun setApprovalRequired(on: Boolean) {
        approvalRequired = on
        prefs.edit().putBoolean("approval", on).apply()
        ui?.onPeersChanged()
    }

    fun approve(id: String) {
        approved.add(id)
        prefs.edit().putStringSet("approved", HashSet(approved)).apply()
        ui?.onPeersChanged()
    }

    fun clearApprovals() {
        approved.clear()
        prefs.edit().remove("approved").apply()
        ui?.onPeersChanged()
    }

    fun isApproved(id: String) = approved.contains(id)

    fun isPending(p: Peer): Boolean =
        mode == "server" && approvalRequired && !approved.contains(p.id) && !blocked.contains(p.id)

    /** Pares que não são servidores (clientes e dispositivos Bluetooth). */
    fun clients(): List<Peer> = allPeers().filter { it.role != "S" }

    private val favorites: MutableMap<String, String> = HashMap()

    init {
        for (e in prefs.getStringSet("favorites", emptySet()) ?: emptySet()) {
            val i = e.indexOf('\t')
            if (i > 0) favorites[e.substring(0, i)] = e.substring(i + 1)
        }
    }

    fun favoriteList(): List<Pair<String, String>> = favorites.entries.map { it.key to it.value }.sortedBy { it.second }

    fun isFavorite(id: String) = favorites.containsKey(id)

    fun toggleFavorite(id: String, name: String) {
        if (favorites.remove(id) == null) favorites[id] = name
        prefs.edit().putStringSet("favorites", HashSet(favorites.entries.map { it.key + "\t" + it.value })).apply()
        ui?.onPeersChanged()
    }

    var micPercent: Int = prefs.getInt("mic_pct", 50)
        private set

    fun setMicPercent(p: Int) {
        micPercent = p
        prefs.edit().putInt("mic_pct", p).apply()
        audio?.gain = p / 50f
    }

    private var sessionStart = SystemClock.elapsedRealtime()
    private var serverSeenSince = 0L

    fun sessionSeconds(): Int = ((SystemClock.elapsedRealtime() - sessionStart) / 1000).toInt()

    /** Segundos desde que o servidor escolhido ficou visível, ou -1 se não está visível. */
    fun serverLinkSeconds(): Int {
        if (selectedServerPeer() == null) { serverSeenSince = 0L; return -1 }
        val now = SystemClock.elapsedRealtime()
        if (serverSeenSince == 0L) serverSeenSince = now
        return ((now - serverSeenSince) / 1000).toInt()
    }

    fun wifiSignal(): String {
        return try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val rssi = wm.connectionInfo.rssi
            when {
                rssi <= -127 || rssi == 0 -> "Indisponível"
                rssi >= -55 -> "Excelente"
                rssi >= -67 -> "Boa"
                rssi >= -80 -> "Razoável"
                else -> "Fraca"
            }
        } catch (e: Exception) { "Indisponível" }
    }

    fun networkLabel(): String = when {
        lan.hasP2pLink() -> "Wi-Fi Direct"
        lan.hasLinks() -> "Wi-Fi"
        bt.isEnabled() -> "Bluetooth"
        else -> "Sem rede"
    }

    fun clearLog() { synchronized(logLines) { logLines.clear() }; ui?.onLog() }

    fun setName(n: String) {
        displayName = n.trim().ifBlank { "Utilizador" }
        prefs.edit().putString("name", displayName).apply()
        lan.wake()
    }

    fun transportFor(peerId: String): Transport = if (peerId.startsWith("bt:")) bt else lan

    fun allPeers(): List<Peer> = (lan.peers() + bt.peers()).map { it.copy(blocked = blocked.contains(it.id)) }

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
        state = State.OUTGOING; peer = p; callId = id; ringing = false; incomingCall = false
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

    override fun onControl(peerId: String, text: String) {
        val plain = Secure.decryptText(text)
        if (plain == null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDecryptLog > 10_000) {
                lastDecryptLog = now
                log("Mensagem recebida mas não decifrada. A senha é igual nos dois telemóveis?")
            }
            return
        }
        main.post { handleControl(peerId, plain) }
    }

    override fun onAudio(peerId: String, seq: Int, pcm: ByteArray) {
        if (state == State.ACTIVE && peerId == peer?.id) {
            val plain = Secure.decrypt(pcm) ?: return
            lastRemote = SystemClock.elapsedRealtime()
            audio?.onRemoteFrame(seq, Codec.decode(plain))
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
                if (blocked.contains(peerId)) { log("Chamada de $peerId ignorada (bloqueado)"); return }
                if (mode == "server" && approvalRequired && !approved.contains(peerId)) {
                    log("Chamada de $peerId recusada: cliente ainda não aprovado")
                    allPeers().firstOrNull { it.id == peerId }?.let { send(it, "BUSY|$cid") }
                    return
                }
                val p = allPeers().firstOrNull { it.id == peerId }
                    ?: Peer(peerId, name, if (peerId.startsWith("bt:")) PeerKind.BLUETOOTH else PeerKind.LAN)
                if (state == State.IDLE) {
                    state = State.INCOMING; peer = p; callId = cid; incomingCall = true
                    statusText = "Chamada de ${p.name}"
                    lastCallSeen = SystemClock.elapsedRealtime()
                    log("Chamada recebida de ${p.name}")
                    send(p, "RINGING|$cid")
                    startRinging()
                    if (ui == null) showIncomingNotification(p)
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

    private fun send(p: Peer, text: String) {
        try { transportFor(p.id).sendControl(p.id, Secure.encryptText(text)) } catch (e: Exception) { log("Envio cifrado falhou: ${e.message}") }
    }

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
        hideIncomingNotification()
        state = State.ACTIVE
        activeSince = SystemClock.elapsedRealtime()
        lastRemote = activeSince
        statusText = "Em chamada com ${p.name}"
        speaker = defaultSpeaker
        log("Chamada ativa com ${p.name}")
        try { CallService.start(app, "Em chamada com ${p.name}") } catch (e: Exception) { log("Serviço de chamada não arrancou: ${e.message}") }
        acquireLocks()
        val t = transportFor(p.id)
        seq = 0
        val a = AudioEngine(app, t.audioRate) { buf, n ->
            seq = (seq + 1) and 0xFFFF
            val packed = Codec.encode(buf, n)
            val sealed = Secure.encrypt(packed, packed.size)
            t.sendAudio(p.id, seq, sealed, sealed.size)
        }
        a.muted = muted
        a.gain = micPercent / 50f
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
        hideIncomingNotification()
        audio?.stop(); audio = null
        releaseLocks()
        try { CallService.stop(app) } catch (_: Exception) {}
        if (p != null) {
            val secs = if (state == State.ACTIVE) ((SystemClock.elapsedRealtime() - activeSince) / 1000).toInt() else 0
            historyList.add(0, CallRecord(p.id, p.name, incomingCall, System.currentTimeMillis(), secs, message))
            while (historyList.size > 50) historyList.removeAt(historyList.size - 1)
        }
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

    private fun showIncomingNotification(p: Peer) {
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("vozlivre_incoming", "Chamadas recebidas", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null); enableVibration(false)
                }
            )
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(app, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(app, "vozlivre_incoming")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Chamada de ${p.name}")
                .setContentText("Toca para atender")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) { log("Notificação de chamada falhou: ${e.message}") }
    }

    private fun hideIncomingNotification() {
        try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2) } catch (_: Exception) {}
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
