package com.vozlivre.core

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Descoberta e ligação Wi-Fi Direct. Quando o grupo se forma, o Android cria uma
 * interface p2p-* com IP próprio e o LanTransport passa a usá-la sozinho.
 */
@SuppressLint("MissingPermission")
class WifiDirect(
    private val ctx: Context,
    private val lan: LanTransport,
    private val log: (String) -> Unit,
    private val onChange: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile var devices: List<WifiP2pDevice> = emptyList(); private set
    @Volatile var status: String = "Wi-Fi Direct: parado"; private set
    @Volatile var grouped = false; private set
    @Volatile var isOwner = false; private set
    val supported: Boolean get() = manager != null

    private fun setStatus(s: String) { status = s; log("[P2P] $s"); main.post { onChange() } }

    fun start() {
        if (receiver != null) return
        val m = manager ?: run { setStatus("Este telemóvel não tem Wi-Fi Direct"); return }
        channel = m.initialize(ctx, Looper.getMainLooper(), null)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        setStatus(if (on) "Wi-Fi Direct disponível" else "Liga o Wi-Fi do telemóvel para usar Wi-Fi Direct")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> loadPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> loadConnection()
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
        receiver = r
    }

    fun stop() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    fun discover() {
        val m = manager; val ch = channel
        if (m == null || ch == null) { setStatus("Wi-Fi Direct indisponível"); return }
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("A procurar telemóveis... faz o mesmo no outro aparelho") }
                override fun onFailure(reason: Int) { setStatus("Procura falhou (${reasonText(reason)})") }
            })
        } catch (e: SecurityException) { setStatus("Falta permissão para procurar: ${e.message}") }
    }

    fun connect(d: WifiP2pDevice) {
        val m = manager; val ch = channel
        if (m == null || ch == null) return
        val cfg = WifiP2pConfig().apply {
            deviceAddress = d.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        setStatus("A ligar a ${d.deviceName}... aceita o convite no outro telemóvel")
        try {
            m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("Convite enviado a ${d.deviceName}") }
                override fun onFailure(reason: Int) { setStatus("Ligação falhou (${reasonText(reason)})") }
            })
        } catch (e: SecurityException) { setStatus("Falta permissão: ${e.message}") }
    }

    fun createGroup() {
        val m = manager; val ch = channel
        if (m == null || ch == null) { setStatus("Wi-Fi Direct indisponível"); return }
        try {
            m.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { setStatus("Grupo criado. Os clientes já se podem ligar") }
                override fun onFailure(reason: Int) { setStatus("Não foi possível criar o grupo (${reasonText(reason)})") }
            })
        } catch (e: SecurityException) { setStatus("Falta permissão: ${e.message}") }
    }

    fun disconnect() {
        val m = manager; val ch = channel
        if (m == null || ch == null) return
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { setStatus("Grupo Wi-Fi Direct terminado") }
            override fun onFailure(reason: Int) { setStatus("Não foi possível terminar o grupo (${reasonText(reason)})") }
        })
    }

    private fun loadPeers() {
        val m = manager; val ch = channel
        if (m == null || ch == null) return
        try {
            m.requestPeers(ch) { list ->
                devices = list.deviceList.toList()
                main.post { onChange() }
            }
        } catch (_: SecurityException) {}
    }

    private fun loadConnection() {
        val m = manager; val ch = channel
        if (m == null || ch == null) return
        m.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            grouped = info.groupFormed
            isOwner = info.isGroupOwner
            if (info.groupFormed) {
                val owner = info.groupOwnerAddress
                setStatus(if (info.isGroupOwner) "Grupo criado por este telemóvel. À espera do outro..." else "Ligado ao grupo (dono ${owner?.hostAddress})")
                // O cliente conhece o IP do dono do grupo: sonda-o logo. O dono aprende o IP do cliente pelo HELLO.
                if (!info.isGroupOwner && owner != null) lan.probeAddress(owner)
                lan.wake()
                main.postDelayed({ lan.wake(); owner?.let { if (!info.isGroupOwner) lan.probeAddress(it) } }, 1500)
            } else {
                setStatus("Wi-Fi Direct: sem grupo")
            }
        }
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "não suportado"
        WifiP2pManager.BUSY -> "ocupado, tenta novamente"
        WifiP2pManager.ERROR -> "erro interno; liga o Wi-Fi e a localização"
        else -> "código $r"
    }
}
