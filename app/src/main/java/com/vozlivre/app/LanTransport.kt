package com.vozlivre.app

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Transporte UDP para qualquer rede IP local: Wi-Fi de um router, hotspot de um
 * telemóvel e grupo Wi-Fi Direct (que para o Android é só mais uma interface).
 *
 * Em vez de um socket genérico, abre sockets presos ao IP de cada interface.
 * Assim o tráfego sai sempre pela interface certa, mesmo que o Android prefira
 * os dados móveis como rede por defeito. A descoberta usa o endereço de
 * broadcast da sub-rede (não 255.255.255.255), uma varredura unicast da sub-rede
 * e sondagem direta por IP.
 */
class LanTransport(
    private val ctx: Context,
    private val myId: String,
    private val nameProvider: () -> String,
    private val roleProvider: () -> String = { "" }
) : Transport {

    companion object {
        const val CONTROL_PORT = 39871
        const val AUDIO_PORT = 39872
        private const val PEER_TIMEOUT_MS = 12_000L
        private val IF_PREFIXES = listOf("wlan", "p2p", "ap", "swlan", "softap", "eth", "rndis", "bt-pan", "bnep", "usb")
    }

    override val audioRate: Int = 16000
    @Volatile var listener: TransportListener? = null

    private class Link(val ifName: String, val addr: Inet4Address, val prefix: Int) {
        val ipInt: Int = ByteBuffer.wrap(addr.address).int
        val mask: Int = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val bcast: InetAddress = intToAddr(ipInt or mask.inv())
        @Volatile var control: DatagramSocket? = null
        @Volatile var audio: DatagramSocket? = null
        val key: String get() = ifName + "/" + addr.hostAddress
        fun contains(a: InetAddress): Boolean = (ipInt2(a) and mask) == (ipInt and mask)
    }

    private class LanPeer(val id: String, var name: String, var ip: InetAddress, var linkKey: String, var seen: Long, var role: String = "")

    private val links = ConcurrentHashMap<String, Link>()
    private val lanPeers = ConcurrentHashMap<String, LanPeer>()
    private val probeQueue = ConcurrentLinkedQueue<InetAddress>()
    private val lastErr = ConcurrentHashMap<String, String>()
    private val io = Executors.newSingleThreadExecutor()
    private val gate = Semaphore(0)
    @Volatile private var running = false
    private var wildcard: DatagramSocket? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var worker: Thread? = null
    private var lastIfDump = ""

    private fun log(m: String) { listener?.onLog("[LAN] $m") }

    // ---------------------------------------------------------------- ciclo de vida

    fun start() {
        if (running) return
        running = true
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mcLock = wm.createMulticastLock("vozlivre-lan").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { log("MulticastLock falhou: ${e.message}") }
        openWildcard()
        worker = thread(name = "lan-worker", isDaemon = true) { workerLoop() }
    }

    fun stop() {
        running = false
        gate.release()
        for (l in links.values) closeLink(l)
        links.clear()
        lanPeers.clear()
        try { wildcard?.close() } catch (_: Exception) {}
        wildcard = null
        try { mcLock?.release() } catch (_: Exception) {}
        mcLock = null
        listener?.onPeersChanged()
    }

    /** Acorda o ciclo de descoberta (por exemplo quando o grupo Wi-Fi Direct se forma). */
    fun wake() { gate.release() }

    /** Envia HELLO direto para um IP (ligação manual ou dono do grupo Wi-Fi Direct). */
    fun probe(ip: String) {
        io.execute {
            try {
                probeQueue.add(InetAddress.getByName(ip.trim()))
                log("A sondar $ip")
                gate.release()
            } catch (e: Exception) { log("IP inválido: $ip") }
        }
    }

    fun probeAddress(a: InetAddress) { probeQueue.add(a); gate.release() }

    fun hasLinks(): Boolean = links.isNotEmpty()

    fun describeLinks(): String {
        if (links.isEmpty()) return "Sem rede local ativa"
        return links.values.sortedBy { it.ifName }.joinToString("\n") { "${it.ifName}: ${it.addr.hostAddress}/${it.prefix}" }
    }

    // ---------------------------------------------------------------- Transport

    override fun connect(peerId: String, cb: (ok: Boolean, error: String?) -> Unit) {
        if (lanPeers.containsKey(peerId)) cb(true, null) else cb(false, "pessoa já não está visível")
    }

    override fun sendControl(peerId: String, text: String) {
        io.execute {
            val p = lanPeers[peerId] ?: return@execute
            val s = links[p.linkKey]?.control ?: wildcard ?: return@execute
            sendTo(s, "MSG|$myId|$text", p.ip, CONTROL_PORT, p.linkKey)
        }
    }

    override fun sendAudio(peerId: String, seq: Int, pcm: ByteArray, len: Int) {
        val p = lanPeers[peerId] ?: return
        val s = links[p.linkKey]?.audio ?: return
        val data = ByteArray(len + 2)
        data[0] = (seq shr 8).toByte()
        data[1] = seq.toByte()
        System.arraycopy(pcm, 0, data, 2, len)
        try { s.send(DatagramPacket(data, data.size, p.ip, AUDIO_PORT)) } catch (_: Exception) {}
    }

    override fun peers(): List<Peer> =
        lanPeers.values.map {
            val via = links[it.linkKey]?.ifName ?: ""
            Peer(it.id, it.name, PeerKind.LAN, via, it.ip.hostAddress ?: "", it.role)
        }.sortedBy { it.name }

    // ---------------------------------------------------------------- envio

    private fun sendTo(s: DatagramSocket, text: String, dest: InetAddress, port: Int, tag: String) {
        try {
            val b = text.toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(b, b.size, dest, port))
            lastErr.remove(tag)
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            if (lastErr.put(tag, m) != m) log("Envio falhou ($tag → ${dest.hostAddress}): $m")
        }
    }

    private fun hello(l: Link, dest: InetAddress) {
        val s = l.control ?: return
        sendTo(s, "HELLO|$myId|${safe(nameProvider())}|${roleProvider()}", dest, CONTROL_PORT, l.key + ">" + dest.hostAddress)
    }

    // ---------------------------------------------------------------- ciclo de descoberta

    private fun workerLoop() {
        var tick = 0
        while (running) {
            try {
                syncLinks()
                expirePeers()
                for (l in links.values) hello(l, l.bcast)
                for (p in lanPeers.values) links[p.linkKey]?.let { hello(it, p.ip) }
                while (true) {
                    val a = probeQueue.poll() ?: break
                    val l = links.values.firstOrNull { it.contains(a) }
                    if (l != null) hello(l, a) else for (x in links.values) hello(x, a)
                }
                if (tick % 4 == 0 && lanPeers.isEmpty()) for (l in links.values) sweep(l)
            } catch (e: Exception) { log("Ciclo: ${e.message}") }
            tick++
            try { gate.tryAcquire(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            gate.drainPermits()
        }
    }

    private fun sweep(l: Link) {
        val sweepMask = if (l.prefix >= 24) l.mask else 0xFFFFFF00.toInt()
        val base = l.ipInt and sweepMask
        val last = base or sweepMask.inv()
        var host = base + 1
        var count = 0
        while (host < last && count < 254) {
            if (host != l.ipInt) hello(l, intToAddr(host))
            host++; count++
        }
    }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        val iter = lanPeers.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val gone = now - e.value.seen > PEER_TIMEOUT_MS || !links.containsKey(e.value.linkKey)
            if (gone) { iter.remove(); changed = true; log("Perdido: ${e.value.name}") }
        }
        if (changed) listener?.onPeersChanged()
    }

    // ---------------------------------------------------------------- interfaces

    private fun scanLinks(): List<Link> {
        val out = ArrayList<Link>()
        val dump = StringBuilder()
        val en: java.util.Enumeration<NetworkInterface>? = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        }
        if (en == null) return out
        for (ni in en) {
            try {
                val name = ni.name ?: continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address) dump.append(name).append('=').append(a.hostAddress).append('/').append(ia.networkPrefixLength).append(if (ni.isUp) "" else "(down)").append(' ')
                }
                if (!ni.isUp || ni.isLoopback) continue
                val lower = name.lowercase()
                if (IF_PREFIXES.none { lower.startsWith(it) }) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    val prefix = ia.networkPrefixLength.toInt()
                    if (a is Inet4Address && (a.isSiteLocalAddress || a.isLinkLocalAddress) && prefix in 8..30) {
                        out.add(Link(name, a, prefix))
                    }
                }
            } catch (_: Exception) {}
        }
        val d = dump.toString()
        if (d != lastIfDump) { lastIfDump = d; log("Interfaces do sistema: $d") }
        return out
    }

    private fun syncLinks() {
        val found = scanLinks()
        val keys = found.map { it.key }.toSet()
        var changed = false
        for ((k, l) in links.entries.toList()) {
            if (k !in keys) { closeLink(l); links.remove(k); changed = true; log("Interface removida: ${l.ifName}") }
        }
        for (f in found) {
            if (!links.containsKey(f.key) && openLink(f)) {
                links[f.key] = f; changed = true
                log("Interface ativa: ${f.ifName} ${f.addr.hostAddress}/${f.prefix} (broadcast ${f.bcast.hostAddress})")
            }
        }
        if (changed) listener?.onPeersChanged()
    }

    private fun openLink(l: Link): Boolean {
        return try {
            val c = DatagramSocket(null).apply {
                reuseAddress = true; broadcast = true
                bind(InetSocketAddress(l.addr, CONTROL_PORT))
            }
            val a = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(l.addr, AUDIO_PORT))
                try { trafficClass = 0xB8 } catch (_: Exception) {}
            }
            l.control = c; l.audio = a
            thread(name = "lan-ctl-${l.ifName}", isDaemon = true) { receiveControl(c, l) }
            thread(name = "lan-aud-${l.ifName}", isDaemon = true) { receiveAudio(a) }
            true
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            if (lastErr.put("bind-" + l.key, m) != m) log("Não foi possível abrir ${l.ifName} ${l.addr.hostAddress}: $m")
            false
        }
    }

    private fun closeLink(l: Link) {
        try { l.control?.close() } catch (_: Exception) {}
        try { l.audio?.close() } catch (_: Exception) {}
        l.control = null; l.audio = null
    }

    private fun openWildcard() {
        try {
            val w = DatagramSocket(null).apply {
                reuseAddress = true; broadcast = true
                bind(InetSocketAddress(CONTROL_PORT))
            }
            wildcard = w
            thread(name = "lan-ctl-any", isDaemon = true) { receiveControl(w, null) }
            log("Escuta de broadcast ativa na porta $CONTROL_PORT")
        } catch (e: Exception) { log("Escuta de broadcast falhou: ${e.message}") }
    }

    // ---------------------------------------------------------------- receção

    private fun receiveControl(sock: DatagramSocket, link: Link?) {
        val buf = ByteArray(2048)
        while (running && !sock.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                handleControl(p, link)
            } catch (e: Exception) {
                if (sock.isClosed) break
            }
        }
    }

    private fun handleControl(p: DatagramPacket, link: Link?) {
        val src = p.address as? Inet4Address ?: return
        val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
        val parts = if (text.startsWith("HELLO|")) text.split("|", limit = 4) else text.split("|", limit = 3)
        if (parts.size < 3) return
        val id = parts[1]
        if (id == myId) return
        val lk = link ?: links.values.firstOrNull { it.contains(src) }
        val linkKey = lk?.key ?: return
        val now = System.currentTimeMillis()
        when (parts[0]) {
            "HELLO" -> {
                val name = parts[2].replace('_', ' ')
                val role = if (parts.size > 3) parts[3] else ""
                val old = lanPeers[id]
                val peer = old ?: LanPeer(id, name, src, linkKey, now, role)
                val changed = old == null || old.name != name || old.ip != src || old.linkKey != linkKey || old.role != role
                peer.name = name; peer.ip = src; peer.linkKey = linkKey; peer.seen = now; peer.role = role
                lanPeers[id] = peer
                if (old == null) {
                    log("Encontrado: $name em ${src.hostAddress} via ${lk.ifName}")
                    hello(lk, src) // responde logo para o outro lado nos descobrir depressa
                }
                if (changed) listener?.onPeersChanged()
            }
            "MSG" -> {
                val old = lanPeers[id]
                if (old == null) {
                    lanPeers[id] = LanPeer(id, "Desconhecido", src, linkKey, now)
                    listener?.onPeersChanged()
                } else { old.ip = src; old.linkKey = linkKey; old.seen = now }
                listener?.onControl(id, parts[2])
            }
        }
    }

    private fun receiveAudio(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running && !sock.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                if (p.length <= 2) continue
                val src = p.address
                val peer = lanPeers.values.firstOrNull { it.ip == src } ?: continue
                val seq = ((p.data[p.offset].toInt() and 0xFF) shl 8) or (p.data[p.offset + 1].toInt() and 0xFF)
                val pcm = p.data.copyOfRange(p.offset + 2, p.offset + p.length)
                listener?.onAudio(peer.id, seq, pcm)
            } catch (e: Exception) {
                if (sock.isClosed) break
            }
        }
    }
}

private fun intToAddr(v: Int): InetAddress =
    InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(v).array())

private fun ipInt2(a: InetAddress): Int {
    val b = a.address
    return if (b.size == 4) ByteBuffer.wrap(b).int else 0
}

internal fun safe(value: String): String =
    value.trim().replace("|", " ").replace("\n", " ").replace(' ', '_').take(32).ifBlank { "Utilizador" }
