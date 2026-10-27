package com.vozlivre.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), CallEngine.Ui {

    private companion object {
        val BG = 0xFF101713.toInt()
        val CARD = 0xFF1B2820.toInt()
        val TEXT = 0xFFFFFFFF.toInt()
        val MUTED = 0xFFB8C7BD.toInt()
        val ACCENT = 0xFF17804A.toInt()
        val ACCENT_LIGHT = 0xFFA4E8BB.toInt()
        val RED = 0xFFC0392B.toInt()
        val GREY = 0xFF2F4036.toInt()
        const val REQ_ENABLE_BT = 21
    }

    private lateinit var engine: CallEngine
    private val handler = Handler(Looper.getMainLooper())
    private var tab = 0
    private var showLog = false

    private val pending = HashMap<Int, () -> Unit>()
    private var reqCode = 100

    private lateinit var nameInput: EditText
    private lateinit var tabButtons: List<Button>
    private lateinit var wifiPanel: LinearLayout
    private lateinit var p2pPanel: LinearLayout
    private lateinit var btPanel: LinearLayout
    private lateinit var wifiInfo: TextView
    private lateinit var ipInput: EditText
    private lateinit var p2pStatus: TextView
    private lateinit var p2pList: LinearLayout
    private lateinit var btStatus: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var logToggle: Button

    private lateinit var callLayer: LinearLayout
    private lateinit var callName: TextView
    private lateinit var callStatus: TextView
    private lateinit var btnAnswer: Button
    private lateinit var btnReject: Button
    private lateinit var btnHang: Button
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = (application as VozApp).engine
        buildUi()
        engine.start()
        val base = ArrayList<String>()
        base.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) base.add(Manifest.permission.POST_NOTIFICATIONS)
        withPerms(base) { }
        if (btPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) engine.startBluetooth()
    }

    override fun onStart() {
        super.onStart()
        engine.ui = this
        refreshAll()
    }

    override fun onStop() {
        if (engine.ui === this) engine.ui = null
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && engine.state == CallEngine.State.IDLE) engine.stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- permissões

    private fun p2pPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun btPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withPerms(perms: List<String>, whenOk: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { whenOk(); return }
        val code = reqCode++
        pending[code] = whenOk
        requestPermissions(missing.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pending.remove(requestCode) ?: return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action()
        else toast("Permissão necessária não concedida")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) {
            engine.startBluetooth()
            engine.bt.startScan()
        }
    }

    // ---------------------------------------------------------------- construção da interface

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(text: String, size: Float = 14f, color: Int = TEXT): TextView =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }

    private fun box(color: Int, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun btn(text: String, color: Int = ACCENT, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(TEXT)
            background = box(color)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }

    private fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        col.addView(tv("VozLivre", 30f, ACCENT_LIGHT).apply { gravity = Gravity.CENTER })
        col.addView(tv("Chamadas de voz sem Internet", 14f, MUTED).apply { gravity = Gravity.CENTER }, lp(bottom = 14))

        nameInput = EditText(this).apply {
            hint = "O teu nome"
            setSingleLine(true)
            setTextColor(TEXT); setHintTextColor(MUTED)
            background = box(CARD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setText(engine.displayName)
        }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nameRow.addView(nameInput, lp(0, -2, weight = 1f))
        nameRow.addView(btn("Guardar") {
            engine.setName(nameInput.text.toString())
            toast("Nome guardado: ${engine.displayName}")
        }, lp(-2, -2).apply { leftMargin = dp(8) })
        col.addView(nameRow, lp(bottom = 12))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("Wi-Fi", "Wi-Fi Direct", "Bluetooth")
        tabButtons = names.mapIndexed { i, n -> btn(n, GREY) { tab = i; refreshAll() } }
        tabButtons.forEachIndexed { i, b -> tabs.addView(b, lp(0, -2, weight = 1f).apply { if (i > 0) leftMargin = dp(6) }) }
        col.addView(tabs, lp(bottom = 10))

        // Painel Wi-Fi
        wifiPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiInfo = tv("", 13f, MUTED)
        wifiPanel.addView(wifiInfo, lp(bottom = 8))
        wifiPanel.addView(tv("Se os telemóveis não se encontrarem sozinhos, escreve o IP do outro (aparece no ecrã dele).", 13f, MUTED), lp(bottom = 6))
        ipInput = EditText(this).apply {
            hint = "ex.: 192.168.1.23"
            setSingleLine(true)
            setTextColor(TEXT); setHintTextColor(MUTED)
            background = box(CARD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val ipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ipRow.addView(ipInput, lp(0, -2, weight = 1f))
        ipRow.addView(btn("Procurar") {
            val ip = ipInput.text.toString().trim()
            if (ip.isNotEmpty()) engine.lan.probe(ip)
        }, lp(-2, -2).apply { leftMargin = dp(8) })
        wifiPanel.addView(ipRow)
        col.addView(wifiPanel, lp(bottom = 8))

        // Painel Wi-Fi Direct
        p2pPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val p2pRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        p2pRow.addView(btn("Procurar telemóveis") {
            withPerms(p2pPerms()) { engine.p2p.discover() }
        }, lp(0, -2, weight = 1f))
        p2pRow.addView(btn("Terminar grupo", GREY) { engine.p2p.disconnect() }, lp(0, -2, weight = 1f).apply { leftMargin = dp(8) })
        p2pPanel.addView(p2pRow, lp(bottom = 6))
        p2pStatus = tv("", 13f, MUTED)
        p2pPanel.addView(p2pStatus, lp(bottom = 6))
        p2pPanel.addView(tv("Faz Procurar nos dois telemóveis, toca em Ligar num deles e aceita o convite no outro. Precisa do Wi-Fi ligado (e da localização ligada até ao Android 12).", 12f, MUTED), lp(bottom = 6))
        p2pList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p2pPanel.addView(p2pList)
        col.addView(p2pPanel, lp(bottom = 8))

        // Painel Bluetooth
        btPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btRow.addView(btn("Procurar") { startBluetoothScan() }, lp(0, -2, weight = 1f))
        btRow.addView(btn("Tornar visível", GREY) {
            withPerms(btPerms()) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            }
        }, lp(0, -2, weight = 1f).apply { leftMargin = dp(8) })
        btPanel.addView(btRow, lp(bottom = 6))
        btStatus = tv("", 13f, MUTED)
        btPanel.addView(btStatus, lp(bottom = 6))
        btPanel.addView(tv("Os dois telemóveis precisam de ter o VozLivre aberto. A qualidade é menor (8 kHz) e o alcance é de poucos metros.", 12f, MUTED))
        col.addView(btPanel, lp(bottom = 8))

        col.addView(tv("Pessoas encontradas", 18f), lp(top = 6, bottom = 6))
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(peerList)

        // Diagnóstico
        logToggle = btn("Mostrar diagnóstico", GREY) { showLog = !showLog; refreshAll() }
        col.addView(logToggle, lp(top = 16))
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logBox.addView(btn("Copiar registo", GREY) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("VozLivre", engine.logText()))
            toast("Registo copiado")
        }, lp(top = 8, bottom = 6))
        logView = tv("", 10f, MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        logBox.addView(logView)
        col.addView(logBox)

        root.addView(ScrollView(this).apply { addView(col) })

        // Camada de chamada
        callLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(80), dp(24), dp(48))
            visibility = View.GONE
            isClickable = true
        }
        callName = tv("", 30f).apply { gravity = Gravity.CENTER }
        callStatus = tv("", 18f, ACCENT_LIGHT).apply { gravity = Gravity.CENTER }
        callLayer.addView(callName, lp(top = 40))
        callLayer.addView(callStatus, lp(top = 12))
        callLayer.addView(View(this), lp(-1, 0, weight = 1f))
        btnMute = btn("Silenciar", GREY) { engine.toggleMute() }
        btnSpeaker = btn("Altifalante", GREY) { engine.toggleSpeaker() }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(btnMute, lp(0, -2, weight = 1f))
        tools.addView(btnSpeaker, lp(0, -2, weight = 1f).apply { leftMargin = dp(10) })
        callLayer.addView(tools, lp(bottom = 16))
        btnAnswer = btn("Atender", ACCENT) { engine.answer() }
        btnReject = btn("Recusar", RED) { engine.reject() }
        btnHang = btn("Terminar chamada", RED) { engine.hangup() }
        callLayer.addView(btnAnswer, lp(bottom = 10))
        callLayer.addView(btnReject, lp(bottom = 10))
        callLayer.addView(btnHang)
        root.addView(callLayer, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    // ---------------------------------------------------------------- atualização

    private fun refreshAll() {
        tabButtons.forEachIndexed { i, b -> b.background = box(if (i == tab) ACCENT else GREY) }
        wifiPanel.visibility = if (tab == 0) View.VISIBLE else View.GONE
        p2pPanel.visibility = if (tab == 1) View.VISIBLE else View.GONE
        btPanel.visibility = if (tab == 2) View.VISIBLE else View.GONE
        logBox.visibility = if (showLog) View.VISIBLE else View.GONE
        logToggle.text = if (showLog) "Esconder diagnóstico" else "Mostrar diagnóstico"
        onPeersChanged()
        onLog()
        onStateChanged()
    }

    override fun onPeersChanged() {
        wifiInfo.text = "O teu IP:\n" + engine.lan.describeLinks()
        p2pStatus.text = engine.p2p.status
        btStatus.text = when {
            !engine.bt.available -> "Este telemóvel não tem Bluetooth"
            !engine.bt.isEnabled() -> "Bluetooth desligado. Toca em Procurar para ligar."
            else -> "Bluetooth ligado"
        }

        p2pList.removeAllViews()
        if (tab == 1) {
            for (d in engine.p2p.devices) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = box(CARD)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                row.addView(tv("${d.deviceName.ifBlank { "Sem nome" }}\n${p2pState(d.status)}", 15f), lp(0, -2, weight = 1f))
                row.addView(btn("Ligar") { engine.p2p.connect(d) }, lp(-2, -2))
                p2pList.addView(row, lp(bottom = 8))
            }
        }

        peerList.removeAllViews()
        val shown = engine.allPeers().filter {
            when (tab) {
                0 -> it.kind == PeerKind.LAN && !it.isP2p
                1 -> it.kind == PeerKind.LAN && it.isP2p
                else -> it.kind == PeerKind.BLUETOOTH
            }
        }
        if (shown.isEmpty()) {
            peerList.addView(tv("Ninguém encontrado ainda. Abre o VozLivre no outro telemóvel.", 14f, MUTED))
        }
        val busy = engine.state != CallEngine.State.IDLE
        for (p in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = box(CARD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            row.addView(tv("${p.name}\n${p.detail}", 16f), lp(0, -2, weight = 1f))
            row.addView(btn("Ligar") {
                withPerms(listOf(Manifest.permission.RECORD_AUDIO)) { engine.startCall(p) }
            }.apply { isEnabled = !busy; alpha = if (busy) 0.4f else 1f }, lp(-2, -2))
            peerList.addView(row, lp(bottom = 8))
        }
    }

    override fun onLog() {
        if (showLog) logView.text = engine.logText()
    }

    override fun onToast(msg: String) { toast(msg) }

    override fun onStateChanged() {
        val st = engine.state
        callLayer.visibility = if (st == CallEngine.State.IDLE) View.GONE else View.VISIBLE
        val p = engine.peer
        callName.text = p?.name ?: ""
        btnAnswer.visibility = if (st == CallEngine.State.INCOMING) View.VISIBLE else View.GONE
        btnReject.visibility = if (st == CallEngine.State.INCOMING) View.VISIBLE else View.GONE
        btnHang.visibility = if (st == CallEngine.State.OUTGOING || st == CallEngine.State.ACTIVE) View.VISIBLE else View.GONE
        val tools = if (st == CallEngine.State.ACTIVE) View.VISIBLE else View.GONE
        btnMute.visibility = tools; btnSpeaker.visibility = tools
        btnMute.text = if (engine.muted) "Ativar microfone" else "Silenciar"
        btnMute.background = box(if (engine.muted) RED else GREY)
        btnSpeaker.background = box(if (engine.speaker) ACCENT else GREY)
        updateCallText()
        handler.removeCallbacks(ticker)
        if (st == CallEngine.State.ACTIVE) handler.postDelayed(ticker, 1000)
        onPeersChanged()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateCallText()
            if (engine.state == CallEngine.State.ACTIVE) handler.postDelayed(this, 1000)
        }
    }

    private fun updateCallText() {
        if (engine.state == CallEngine.State.ACTIVE) {
            val s = (SystemClock.elapsedRealtime() - engine.activeSince) / 1000
            callStatus.text = "Em chamada  %02d:%02d".format(s / 60, s % 60)
        } else callStatus.text = engine.statusText
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun startBluetoothScan() {
        withPerms(btPerms()) {
            if (!engine.bt.available) { toast("Sem Bluetooth neste telemóvel"); return@withPerms }
            if (!engine.bt.isEnabled()) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } else {
                engine.startBluetooth()
                engine.bt.startScan()
            }
        }
    }

    private fun p2pState(v: Int) = when (v) {
        android.net.wifi.p2p.WifiP2pDevice.AVAILABLE -> "disponível"
        android.net.wifi.p2p.WifiP2pDevice.INVITED -> "convite enviado"
        android.net.wifi.p2p.WifiP2pDevice.CONNECTED -> "ligado"
        android.net.wifi.p2p.WifiP2pDevice.FAILED -> "falhou"
        else -> "indisponível"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
