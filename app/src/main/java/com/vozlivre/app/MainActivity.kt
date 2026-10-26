package com.vozlivre.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val controlPort = 39871
    private val audioPort = 39872
    private val audioRate = 16000
    private val packetBytes = 640 // 20 ms, mono PCM16 at 16 kHz
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peers = ConcurrentHashMap<String, Peer>()
    private var displayName = "Utilizador"
    private var running = false
    private var inCall = false
    private var callPeer: Peer? = null
    private var pendingPeer: Peer? = null
    private var controlSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var discoveryThread: Thread? = null
    private var receiveAudioThread: Thread? = null
    private var sendAudioThread: Thread? = null
    private var wifiLock: WifiManager.MulticastLock? = null

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var callStatus: TextView

    data class Peer(val name: String, val ip: String, val port: Int = 39871, val seenAt: Long = System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        } else startLocalNetwork()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 24)
            setBackgroundColor(0xFF101713.toInt())
        }
        val title = TextView(this).apply {
            text = "VozLivre"
            textSize = 30f
            setTextColor(0xFFA4E8BB.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Chamadas locais • Sem Internet"
            textSize = 14f
            setTextColor(0xFFD2E2D7.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 20)
        }
        nameInput = EditText(this).apply {
            hint = "O teu nome"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AA79E.toInt())
            setPadding(18, 10, 18, 10)
            setBackgroundColor(0xFF26352B.toInt())
            setText("Utilizador")
        }
        val saveName = Button(this).apply {
            text = "Guardar nome"
            setOnClickListener {
                displayName = nameInput.text.toString().trim().ifBlank { "Utilizador" }
                toast("Nome guardado: $displayName")
                broadcastHello()
            }
        }
        status = TextView(this).apply {
            text = "A iniciar rede local..."
            textSize = 14f
            setTextColor(0xFFA4E8BB.toInt())
            setPadding(0, 14, 0, 8)
        }
        callStatus = TextView(this).apply {
            text = "Nenhuma chamada ativa"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 8, 0, 8)
        }
        val hangup = Button(this).apply {
            text = "Terminar chamada"
            setOnClickListener { endCall("Chamada terminada") }
        }
        val section = TextView(this).apply {
            text = "Pessoas encontradas na rede"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 18, 0, 8)
        }
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(peerList) }
        root.addView(title)
        root.addView(subtitle)
        root.addView(nameInput)
        root.addView(saveName)
        root.addView(status)
        root.addView(callStatus)
        root.addView(hangup)
        root.addView(section)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocalNetwork()
        } else {
            status.text = "É necessário permitir o microfone para chamadas."
        }
    }

    private fun startLocalNetwork() {
        if (running) return
        running = true
        displayName = nameInput.text.toString().trim().ifBlank { "Utilizador" }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createMulticastLock("vozlivre-multicast").apply { setReferenceCounted(true); acquire() }
        } catch (_: Exception) {}
        discoveryThread = thread(name = "vozlivre-control") {
            try {
                controlSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(controlPort))
                    broadcast = true
                    soTimeout = 1000
                }
                audioSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(audioPort))
                    soTimeout = 1000
                }
                mainHandler.post { status.text = "Rede local ativa. A procurar utilizadores..." }
                thread { receiveControlLoop() }
                thread { receiveAudioLoop() }
                while (running) {
                    broadcastHello()
                    val now = System.currentTimeMillis()
                    peers.entries.removeIf { now - it.value.seenAt > 12000 }
                    mainHandler.post { refreshPeers() }
                    Thread.sleep(3000)
                }
            } catch (e: Exception) {
                mainHandler.post { status.text = "Erro de rede: ${e.message ?: "não foi possível iniciar"}" }
            }
        }
    }

    private fun broadcastHello() {
        try {
            val socket = controlSocket ?: return
            val msg = "HELLO|${safeName(displayName)}|$audioPort"
            val data = msg.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(data, data.size, address, controlPort))
        } catch (_: Exception) {}
    }

    private fun receiveControlLoop() {
        val buffer = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                controlSocket?.receive(packet) ?: continue
                val msg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val ip = packet.address.hostAddress ?: continue
                val parts = msg.split("|")
                if (parts.size < 2) continue
                when (parts[0]) {
                    "HELLO" -> {
                        val name = parts[1].replace("_", " ")
                        if (ip != localIp()) {
                            peers[ip] = Peer(name, ip, if (parts.size > 2) parts[2].toIntOrNull() ?: audioPort else audioPort)
                            mainHandler.post { refreshPeers() }
                        }
                    }
                    "CALL" -> {
                        if (!inCall) {
                            val p = Peer(parts.getOrElse(1) { "Utilizador" }.replace("_", " "), ip)
                            pendingPeer = p
                            mainHandler.post { showIncomingCall(p) }
                        } else sendControl(ip, "BUSY|${safeName(displayName)}")
                    }
                    "ACCEPT" -> {
                        if (callPeer?.ip == ip) {
                            mainHandler.post { callStatus.text = "Em chamada com ${callPeer?.name}" }
                            startAudio(ip)
                        }
                    }
                    "REJECT" -> mainHandler.post { toast("${parts.getOrElse(1) { "A pessoa" }} rejeitou a chamada") }
                    "HANGUP" -> {
                        if (callPeer?.ip == ip) endCall("A outra pessoa terminou a chamada", false)
                        pendingPeer = null
                    }
                    "BUSY" -> mainHandler.post { toast("O utilizador está ocupado") }
                }
            } catch (_: SocketException) {
                if (!running) break
            } catch (_: Exception) {}
        }
    }

    private fun showIncomingCall(peer: Peer) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Chamada recebida")
            .setMessage("${peer.name} está a ligar-te.")
            .setPositiveButton("Atender") { _, _ ->
                if (inCall) return@setPositiveButton
                callPeer = peer
                inCall = true
                sendControl(peer.ip, "ACCEPT|${safeName(displayName)}")
                callStatus.text = "Em chamada com ${peer.name}"
                startAudio(peer.ip)
            }
            .setNegativeButton("Rejeitar") { _, _ ->
                sendControl(peer.ip, "REJECT|${safeName(displayName)}")
                pendingPeer = null
            }
            .setOnCancelListener { pendingPeer = null }
            .show()
    }

    private fun refreshPeers() {
        peerList.removeAllViews()
        if (peers.isEmpty()) {
            peerList.addView(TextView(this).apply {
                text = "Ainda não foram encontrados utilizadores. Mantém a aplicação aberta nos dois telemóveis e confirma que estão na mesma rede Wi-Fi."
                setTextColor(0xFFB8C7BD.toInt())
                textSize = 14f
                setPadding(0, 10, 0, 10)
            })
            return
        }
        peers.values.sortedBy { it.name }.forEach { peer ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                setBackgroundColor(0xFF213127.toInt())
            }
            val label = TextView(this).apply {
                text = "${peer.name}\n${peer.ip}"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            }
            val call = Button(this).apply {
                text = "Ligar"
                isEnabled = !inCall
                setOnClickListener { callPeer(peer) }
            }
            row.addView(label)
            row.addView(call)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = 12
            peerList.addView(row, lp)
        }
    }

    private fun callPeer(peer: Peer) {
        if (inCall) { toast("Já estás numa chamada"); return }
        callPeer = peer
        inCall = true
        callStatus.text = "A chamar ${peer.name}..."
        refreshPeers()
        sendControl(peer.ip, "CALL|${safeName(displayName)}")
        mainHandler.postDelayed({
            if (inCall && callStatus.text.toString().startsWith("A chamar")) {
                endCall("Sem resposta", false)
            }
        }, 30000)
    }

    private fun sendControl(ip: String, message: String) {
        thread {
            try {
                val bytes = message.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), controlPort)) }
            } catch (_: Exception) {}
        }
    }

    private fun startAudio(remoteIp: String) {
        stopAudioOnly()
        try {
            val minIn = AudioRecord.getMinBufferSize(audioRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(audioRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, audioRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, packetBytes * 4))
            track = AudioTrack(AudioManager.STREAM_VOICE_CALL, audioRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minOut, packetBytes * 4),
                AudioTrack.MODE_STREAM)
            val r = record!!
            val t = track!!
            if (r.state != AudioRecord.STATE_INITIALIZED || t.state != AudioTrack.STATE_INITIALIZED) {
                endCall("Não foi possível iniciar o áudio", false); return
            }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            r.startRecording()
            t.play()
            sendAudioThread = thread(name = "voz-send") {
                val buf = ByteArray(packetBytes)
                while (running && inCall && callPeer?.ip == remoteIp) {
                    try {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) {
                            val packet = DatagramPacket(buf, n, InetAddress.getByName(remoteIp), audioPort)
                            audioSocket?.send(packet)
                        }
                    } catch (_: Exception) { break }
                }
            }
            mainHandler.post { callStatus.text = "Em chamada com ${callPeer?.name}" }
        } catch (e: Exception) {
            mainHandler.post { endCall("Erro de áudio: ${e.message}", false) }
        }
    }

    private fun receiveAudioLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                audioSocket?.receive(packet) ?: continue
                val peer = callPeer
                if (!inCall || peer == null || packet.address.hostAddress != peer.ip) continue
                val copy = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val t = track
                if (t != null && t.playState == AudioTrack.PLAYSTATE_PLAYING) t.write(copy, 0, copy.size)
            } catch (_: SocketException) {
                if (!running) break
            } catch (_: Exception) {}
        }
    }

    private fun stopAudioOnly() {
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun endCall(message: String, notifyPeer: Boolean = true) {
        val old = callPeer
        if (notifyPeer && old != null) sendControl(old.ip, "HANGUP|${safeName(displayName)}")
        inCall = false
        callPeer = null
        pendingPeer = null
        stopAudioOnly()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (_: Exception) {}
        callStatus.text = message
        refreshPeers()
    }

    private fun localIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo.ipAddress
            if (raw == 0) null else "${raw and 0xff}.${raw shr 8 and 0xff}.${raw shr 16 and 0xff}.${raw shr 24 and 0xff}"
        } catch (_: Exception) { null }
    }

    private fun safeName(value: String) = value.trim().replace("|", " ").replace("\n", " ").replace(" ", "_").take(32)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        endCall("Aplicação fechada")
        try { controlSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
