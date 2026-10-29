package com.vozlivre.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), CallEngine.Ui, Host {

    private class Tab(val icon: Int, val label: String, val screen: Screen)
    private class NavItem(val icon: ImageView, val label: TextView, val indicator: View)

    private companion object { const val REQ_ENABLE_BT = 21 }

    private lateinit var engine: CallEngine
    private lateinit var k: Kit
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var mainView: View? = null

    private var screens: List<Screen> = emptyList()
    private val navItems = ArrayList<NavItem>()
    private var body: FrameLayout? = null
    private var current = 0
    private var logSection: LogSection? = null
    private var logPending = false

    private var headerDot: View? = null
    private var headerStatus: TextView? = null

    private val pending = HashMap<Int, () -> Unit>()
    private var reqCode = 100

    // chamada
    private lateinit var callLayer: LinearLayout
    private lateinit var ring: View
    private lateinit var avatar: TextView
    private lateinit var callName: TextView
    private lateinit var callStatus: TextView
    private lateinit var toolsRow: LinearLayout
    private lateinit var incomingRow: LinearLayout
    private lateinit var hangCol: LinearLayout
    private lateinit var muteBtn: FrameLayout
    private lateinit var speakerBtn: FrameLayout
    private var pulse: ObjectAnimator? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = (application as VozApp).engine
        k = Kit(this)
        root = FrameLayout(this)
        root.setBackgroundColor(Colors.BG)
        buildOverlay()
        setContentView(root)
        engine.start()
        val base = ArrayList<String>(micPerms())
        base.addAll(notifPerms())
        withPerms(base) { }
        if (allGranted(btPerms())) engine.startBluetooth()
        showMain()
    }

    override fun onStart() {
        super.onStart()
        engine.ui = this
        refreshHeader()
        onStateChanged()
        screens.getOrNull(current)?.refresh()
    }

    override fun onStop() {
        if (engine.ui === this) engine.ui = null
        handler.removeCallbacks(ticker)
        setPulse(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && engine.state == CallEngine.State.IDLE && !engine.listenInBackground) engine.stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Host

    override fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun allGranted(perms: List<String>): Boolean = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun micPerms(): List<String> = listOf(Manifest.permission.RECORD_AUDIO)

    override fun notifPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    override fun p2pPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun btPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun withPerms(perms: List<String>, whenOk: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { whenOk(); return }
        val code = reqCode++
        pending[code] = whenOk
        requestPermissions(missing.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pending.remove(requestCode)
        if (action != null) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action()
            else toast("Permissão necessária não concedida")
        }
        if (allGranted(btPerms())) engine.startBluetooth()
        screens.getOrNull(current)?.refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) {
            engine.startBluetooth()
            engine.bt.startScan()
        }
        screens.getOrNull(current)?.refresh()
    }

    override fun scanBluetooth() {
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

    override fun makeDiscoverable() {
        withPerms(btPerms()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        }
    }

    override fun scanAll() {
        engine.lan.wake()
        val all = (p2pPerms() + btPerms()).distinct()
        withPerms(all) {
            if (engine.bt.available && engine.bt.isEnabled()) { engine.startBluetooth(); engine.bt.startScan() }
            engine.p2p.discover()
        }
    }

    override fun openBatterySettings() {
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) { toast("Abre as definições de bateria do Android") }
    }

    override fun changeMode() {
        if (engine.state != CallEngine.State.IDLE) { toast("Termina a chamada primeiro"); return }
        engine.setMode(null)
        showMain()
    }

    override fun goTo(index: Int) { select(index) }

    override fun getVolume(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) * 100 / max
    }

    override fun setVolume(pct: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, pct * max / 100, 0) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- escolha de modo e estrutura

    private fun showMain() {
        mainView?.let { root.removeView(it) }
        logSection = null
        navItems.clear()
        screens = emptyList()
        val v = if (engine.mode == null) buildChooser() else buildShell()
        mainView = v
        root.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
        refreshHeader()
    }

    private fun buildChooser(): View {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(k.dp(24), k.dp(56), k.dp(24), k.dp(24))

        val sp = SpannableString("VozLivre")
        sp.setSpan(ForegroundColorSpan(Colors.GREEN), 3, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        col.addView(k.text(sp, 36f, Colors.TEXT, true))
        col.addView(k.muted("Escolhe como vais usar esta app neste telemóvel.", 15f), k.lp(top = 8, bottom = 28))

        fun option(icon: Int, title: String, desc: String, mode: String) {
            val c = k.card(true)
            c.isClickable = true
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(k.icon(icon, Colors.GREEN, 30))
            top.addView(k.text(title, 20f, Colors.TEXT, true), k.lp(0, -2, left = 12, weight = 1f))
            c.addView(top)
            c.addView(k.muted(desc, 14f), k.lp(top = 10))
            c.setOnClickListener {
                engine.setMode(mode)
                showMain()
            }
            col.addView(c, k.lp(bottom = 16))
        }
        option(R.drawable.ic_server, "Servidor",
            "Painel de controlo completo: rede, clientes, chamadas, segurança, registos e definições avançadas.", "server")
        option(R.drawable.ic_call, "Cliente",
            "Interface simples: liga ao servidor, faz chamadas e controla o áudio.", "client")

        col.addView(View(this), k.lp(-1, 0, weight = 1f))
        col.addView(k.muted(Brand.SIGNATURE, 12f), k.lp(top = 16))
        sv.addView(col, FrameLayout.LayoutParams(-1, -2))
        return sv
    }

    private fun buildShell(): View {
        val server = engine.mode == "server"
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(buildHeader(server))
        val b = FrameLayout(this)
        body = b
        col.addView(b, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = if (server) serverTabs() else clientTabs()
        screens = tabs.map { it.screen }
        col.addView(buildNav(tabs))
        current = 0
        select(0)
        return col
    }

    private fun serverTabs(): List<Tab> {
        val log = LogSection(k, engine, this)
        logSection = log
        return listOf(
            Tab(R.drawable.ic_wifi, "Ligações", Screen(k, listOf(
                ServerOverviewSection(k, engine), NetworkSection(k, engine),
                P2pSection(k, engine, this, true), BtSection(k, engine, this)))),
            Tab(R.drawable.ic_people, "Clientes", Screen(k, listOf(ClientsSection(k, engine, this)))),
            Tab(R.drawable.ic_call, "Chamadas", Screen(k, listOf(
                QuickDialSection(k, engine, this), CallStatsSection(k, engine), HistorySection(k, engine, 30)))),
            Tab(R.drawable.ic_pulse, "Diagnóstico", Screen(k, listOf(PeersTableSection(k, engine), log))),
            Tab(R.drawable.ic_settings, "Definições", Screen(k, listOf(
                IdentitySection(k, engine, this), PermissionsSection(k, this), BackgroundSection(k, engine, this),
                AdvancedSection(k, engine, this), ModeSection(k, engine, this), CreditsSection(k))))
        )
    }

    private fun clientTabs(): List<Tab> = listOf(
        Tab(R.drawable.ic_wifi, "Ligação", Screen(k, listOf(
            ClientLinkStatusSection(k, engine, this), ServerListSection(k, engine, this),
            OtherMethodsSection(k, engine, this), IdentitySection(k, engine, this)))),
        Tab(R.drawable.ic_call, "Chamadas", Screen(k, listOf(
            CallServerSection(k, engine, this), HistorySection(k, engine, 10, "Chamadas recentes")))),
        Tab(R.drawable.ic_speaker, "Áudio", Screen(k, listOf(AudioControlsSection(k, engine, this)))),
        Tab(R.drawable.ic_bars, "Estado", Screen(k, listOf(
            ClientIndicatorsSection(k, engine, this), PermissionsSection(k, this), BackgroundSection(k, engine, this),
            ModeSection(k, engine, this), CreditsSection(k))))
    )

    private fun buildHeader(server: Boolean): View {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(8))
        val sp = SpannableString("VozLivre")
        sp.setSpan(ForegroundColorSpan(Colors.GREEN), 3, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        h.addView(k.text(sp, 22f, Colors.TEXT, true))
        h.addView(k.pill(if (server) "SERVIDOR" else "CLIENTE"), k.lp(-2, -2, left = 10))
        h.addView(View(this), k.lp(0, 1, weight = 1f))
        val d = k.dot(Colors.AMBER, 12)
        headerDot = d
        h.addView(d)
        val st = k.text("", 12f, Colors.MUTED)
        headerStatus = st
        h.addView(st, k.lp(-2, -2, left = 8))
        return h
    }

    private fun buildNav(tabs: List<Tab>): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(Colors.SURFACE)
        wrap.addView(k.divider())
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for ((i, t) in tabs.withIndex()) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(0, k.dp(6), 0, k.dp(8))
            val ind = View(this)
            ind.background = k.shape(Colors.GREEN, 3)
            k.glow(ind, Colors.GREEN, 4)
            item.addView(ind, k.lp(k.dp(22), k.dp(3), bottom = 6))
            val ic = k.icon(t.icon, Colors.MUTED, 22)
            item.addView(ic)
            val tx = k.text(t.label, 10.5f, Colors.MUTED)
            item.addView(tx, k.lp(-2, -2, top = 3))
            item.setOnClickListener { select(i) }
            navItems.add(NavItem(ic, tx, ind))
            bar.addView(item, k.lp(0, -2, weight = 1f))
        }
        wrap.addView(bar)
        return wrap
    }

    private fun select(i: Int) {
        val b = body ?: return
        if (i < 0 || i >= screens.size) return
        current = i
        b.removeAllViews()
        b.addView(screens[i].view, FrameLayout.LayoutParams(-1, -1))
        for ((j, n) in navItems.withIndex()) {
            val sel = j == i
            n.icon.setColorFilter(if (sel) Colors.GREEN else Colors.MUTED)
            n.label.setTextColor(if (sel) Colors.GREEN else Colors.MUTED)
            n.indicator.visibility = if (sel) View.VISIBLE else View.INVISIBLE
        }
        screens[i].refresh()
    }

    private fun refreshHeader() {
        val d = headerDot ?: return
        val s = headerStatus ?: return
        if (engine.mode == "server") {
            val up = engine.lan.hasLinks()
            k.recolorDot(d, if (up) Colors.GREEN else Colors.AMBER)
            s.text = if (up) "Ativo" else "Sem rede"
        } else {
            val ok = engine.selectedServerPeer() != null
            k.recolorDot(d, if (ok) Colors.GREEN else Colors.AMBER)
            s.text = if (ok) "Ligado" else "Sem servidor"
        }
    }

    // ---------------------------------------------------------------- CallEngine.Ui

    override fun onPeersChanged() {
        refreshHeader()
        screens.getOrNull(current)?.refresh()
    }

    override fun onLog() {
        if (logPending) return
        logPending = true
        handler.postDelayed({
            logPending = false
            logSection?.refresh()
        }, 400)
    }

    override fun onToast(msg: String) { toast(msg) }

    override fun onStateChanged() {
        val st = engine.state
        callLayer.visibility = if (st == CallEngine.State.IDLE) View.GONE else View.VISIBLE
        val p = engine.peer
        callName.text = p?.name ?: ""
        avatar.text = p?.name?.trim()?.firstOrNull()?.uppercase() ?: "?"
        incomingRow.visibility = if (st == CallEngine.State.INCOMING) View.VISIBLE else View.GONE
        hangCol.visibility = if (st == CallEngine.State.OUTGOING || st == CallEngine.State.ACTIVE) View.VISIBLE else View.GONE
        toolsRow.visibility = if (st == CallEngine.State.ACTIVE) View.VISIBLE else View.GONE
        muteBtn.background = circle(if (engine.muted) Colors.RED else Colors.CARD_HI)
        (muteBtn.tag as ImageView).setColorFilter(if (engine.muted) Color.WHITE else Colors.TEXT)
        speakerBtn.background = circle(if (engine.speaker) Colors.GREEN else Colors.CARD_HI)
        (speakerBtn.tag as ImageView).setColorFilter(if (engine.speaker) Color.BLACK else Colors.TEXT)
        updateCallText()
        setPulse(st == CallEngine.State.OUTGOING || st == CallEngine.State.INCOMING)
        handler.removeCallbacks(ticker)
        if (st == CallEngine.State.ACTIVE) handler.postDelayed(ticker, 1000)
        refreshHeader()
        screens.getOrNull(current)?.refresh()
    }

    // ---------------------------------------------------------------- ecrã de chamada

    private fun circle(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun round(icon: Int, fill: Int, rotate: Float = 0f, tint: Int = Color.WHITE, glow: Boolean = false, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = circle(fill)
        f.setOnClickListener { onClick() }
        val img = ImageView(this)
        img.setImageResource(icon)
        img.setColorFilter(tint)
        img.rotation = rotate
        f.addView(img, FrameLayout.LayoutParams(k.dp(30), k.dp(30), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(k.dp(72), k.dp(72))
        f.tag = img
        if (glow) k.glow(f, fill, 8)
        return f
    }

    private fun labeled(b: View, label: String, weight: Float = 0f): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(b)
        val t = k.muted(label, 13f)
        t.gravity = Gravity.CENTER
        c.addView(t, k.lp(top = 6))
        if (weight > 0f) c.layoutParams = LinearLayout.LayoutParams(0, -2, weight)
        return c
    }

    private fun buildOverlay() {
        callLayer = LinearLayout(this)
        callLayer.orientation = LinearLayout.VERTICAL
        callLayer.gravity = Gravity.CENTER_HORIZONTAL
        callLayer.setBackgroundColor(Colors.BG)
        callLayer.setPadding(k.dp(24), k.dp(64), k.dp(24), k.dp(56))
        callLayer.visibility = View.GONE
        callLayer.isClickable = true

        val ringBox = FrameLayout(this)
        ring = View(this)
        ring.background = k.shape(Colors.alpha(Colors.GREEN, 0x10), 200, Colors.alpha(Colors.GREEN, 0x55), 2)
        ringBox.addView(ring, FrameLayout.LayoutParams(k.dp(190), k.dp(190), Gravity.CENTER))
        avatar = k.avatar("?", 120)
        avatar.textSize = 48f
        ringBox.addView(avatar, FrameLayout.LayoutParams(k.dp(120), k.dp(120), Gravity.CENTER))
        callLayer.addView(ringBox, LinearLayout.LayoutParams(k.dp(190), k.dp(190)))

        callName = k.text("", 28f, Colors.TEXT, true)
        callName.gravity = Gravity.CENTER
        callStatus = k.text("", 17f, Colors.GREEN)
        callStatus.gravity = Gravity.CENTER
        callLayer.addView(callName, k.lp(top = 20))
        callLayer.addView(callStatus, k.lp(top = 8))
        callLayer.addView(View(this), k.lp(-1, 0, weight = 1f))

        muteBtn = round(R.drawable.ic_mic, Colors.CARD_HI, tint = Colors.TEXT) { engine.toggleMute() }
        speakerBtn = round(R.drawable.ic_speaker, Colors.CARD_HI, tint = Colors.TEXT) { engine.toggleSpeaker() }
        toolsRow = LinearLayout(this)
        toolsRow.orientation = LinearLayout.HORIZONTAL
        toolsRow.addView(labeled(muteBtn, "Silenciar", 1f))
        toolsRow.addView(labeled(speakerBtn, "Altifalante", 1f))
        callLayer.addView(toolsRow, k.lp(bottom = 28))

        val reject = round(R.drawable.ic_call, Colors.RED, 135f, glow = true) { engine.reject() }
        val answer = round(R.drawable.ic_call, Colors.GREEN, 0f, Color.BLACK, true) { engine.answer() }
        incomingRow = LinearLayout(this)
        incomingRow.orientation = LinearLayout.HORIZONTAL
        incomingRow.addView(labeled(reject, "Recusar", 1f))
        incomingRow.addView(labeled(answer, "Atender", 1f))
        callLayer.addView(incomingRow)

        val hang = round(R.drawable.ic_call, Colors.RED, 135f, glow = true) { engine.hangup() }
        hangCol = labeled(hang, "Terminar")
        callLayer.addView(hangCol)

        root.addView(callLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun setPulse(on: Boolean) {
        if (on) {
            if (pulse == null) {
                val a = ObjectAnimator.ofPropertyValuesHolder(
                    ring,
                    PropertyValuesHolder.ofFloat("scaleX", 1f, 1.12f),
                    PropertyValuesHolder.ofFloat("scaleY", 1f, 1.12f),
                    PropertyValuesHolder.ofFloat("alpha", 1f, 0.35f)
                )
                a.duration = 1100
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.REVERSE
                a.start()
                pulse = a
            }
        } else {
            pulse?.cancel()
            pulse = null
            ring.scaleX = 1f; ring.scaleY = 1f; ring.alpha = 1f
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateCallText()
            if (engine.state == CallEngine.State.ACTIVE) handler.postDelayed(this, 1000)
        }
    }

    private fun updateCallText() {
        if (engine.state == CallEngine.State.ACTIVE) {
            val s = ((SystemClock.elapsedRealtime() - engine.activeSince) / 1000).toInt()
            callStatus.text = "Em chamada  " + formatSeconds(s)
        } else {
            callStatus.text = engine.statusText
        }
    }
}
