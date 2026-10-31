package com.vozlivre.server

import android.annotation.TargetApi
import android.app.Activity
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vozlivre.core.Colors
import com.vozlivre.core.Kit
import com.vozlivre.core.LogoSection
import com.vozlivre.server.security.AdminAccount
import com.vozlivre.server.security.AdminSession
import com.vozlivre.server.security.AdminStore
import com.vozlivre.server.security.LoginResult
import com.vozlivre.server.security.Role

/**
 * Ecrã de acesso do Servidor: configuração inicial (uma única vez), início de sessão,
 * troca da senha inicial e recuperação. Cobre o painel enquanto não houver sessão válida.
 */
class AdminGate(private val act: Activity, private val k: Kit, private val onChange: () -> Unit) {

    private enum class Mode { SETUP_INTRO, SETUP_FORM, KEY_SHOW, LOGIN, FIRST_CHANGE, RECOVER, CORRUPTED }

    val view = FrameLayout(act)
    private var mode: Mode = Mode.LOGIN
    private var rendered: Mode? = null
    private var pendingKey: String? = null
    private var keyActor = ""
    private var pendingAccount: AdminAccount? = null
    private var lockLabel: TextView? = null
    private var loginButton: Button? = null

    val locked: Boolean get() = view.visibility == View.VISIBLE

    init {
        view.setBackgroundColor(Colors.BG)
        view.isClickable = true
        view.visibility = View.GONE
    }

    // ---------------------------------------------------------------- decisão

    fun update() {
        when {
            AdminStore.corrupted -> { mode = Mode.CORRUPTED; render() }
            !AdminStore.initialized -> {
                if (mode != Mode.SETUP_FORM && mode != Mode.KEY_SHOW) mode = Mode.SETUP_INTRO
                render()
            }
            mode == Mode.KEY_SHOW || mode == Mode.FIRST_CHANGE || mode == Mode.RECOVER -> render()
            AdminSession.current() != null -> unlock()
            else -> { mode = Mode.LOGIN; render() }
        }
    }

    /** Chamado a cada segundo: contagem do bloqueio e expiração da sessão. */
    fun tick() {
        if (locked) {
            if (mode == Mode.LOGIN) updateLockLabel()
        } else if (AdminStore.initialized && AdminSession.current() == null) {
            update()
        }
    }

    private fun unlock() {
        mode = Mode.LOGIN
        rendered = null
        pendingKey = null
        pendingAccount = null
        view.removeAllViews()
        view.visibility = View.GONE
        onChange()
    }

    private fun render() {
        if (rendered == mode && view.visibility == View.VISIBLE) return
        rendered = mode
        view.removeAllViews()
        view.visibility = View.VISIBLE
        val v = when (mode) {
            Mode.SETUP_INTRO -> introPage()
            Mode.SETUP_FORM -> setupPage()
            Mode.KEY_SHOW -> keyPage()
            Mode.LOGIN -> loginPage()
            Mode.FIRST_CHANGE -> firstChangePage()
            Mode.RECOVER -> recoverPage()
            Mode.CORRUPTED -> corruptedPage()
        }
        view.addView(v, FrameLayout.LayoutParams(-1, -1))
        onChange()
    }

    private fun go(m: Mode) { mode = m; render() }

    // ---------------------------------------------------------------- componentes

    private fun page(title: String, subtitle: String, vararg items: View): View {
        val sv = ScrollView(act)
        sv.isFillViewport = true
        sv.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF04160D.toInt(), Colors.BG))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(k.dp(24), k.dp(32), k.dp(24), k.dp(24))
        col.addView(LogoSection(k, "Servidor").view)
        col.addView(k.text(title, 22f, Colors.TEXT, true), k.lp(top = 8))
        if (subtitle.isNotEmpty()) col.addView(k.muted(subtitle, 14f), k.lp(top = 6, bottom = 8))
        for (i in items) col.addView(i, k.lp(top = 12))
        sv.addView(col, FrameLayout.LayoutParams(-1, -2))
        return sv
    }

    private fun errorText(): TextView {
        val t = k.text("", 13f, Colors.RED)
        t.visibility = View.GONE
        return t
    }

    private fun showError(t: TextView, msg: String?) {
        if (msg == null) { t.visibility = View.GONE } else { t.text = msg; t.visibility = View.VISIBLE }
    }

    private fun <T> async(btn: Button, working: String, task: () -> T, done: (Result<T>) -> Unit) {
        val label = btn.text
        btn.isEnabled = false
        btn.text = working
        Thread {
            val r = runCatching { task() }
            act.runOnUiThread {
                btn.isEnabled = true
                btn.text = label
                done(r)
            }
        }.start()
    }

    private fun messageOf(e: Throwable): String = e.message ?: "Erro inesperado."

    // ---------------------------------------------------------------- configuração inicial

    private fun introPage(): View {
        val card = k.card(true)
        card.addView(k.text("Configuração inicial", 17f, Colors.TEXT, true))
        card.addView(k.muted("Vais criar o Administrador Principal deste servidor. Esta configuração só pode ser feita uma vez: depois disso, ninguém mais se torna administrador só por instalar a app.", 14f), k.lp(top = 8))
        card.addView(k.muted("O que vai acontecer: definir utilizador e senha forte, registar este telemóvel como dispositivo administrativo e receber uma chave de recuperação, mostrada uma única vez.", 14f), k.lp(top = 8))
        val next = k.button("Começar") { go(Mode.SETUP_FORM) }
        return page("VozLivre Servidor", "Primeiro acesso", card, next)
    }

    private fun setupPage(): View {
        val user = k.input("Nome de utilizador")
        val pw = k.input("Senha (mínimo 10 caracteres)", true)
        val pw2 = k.input("Confirmar a senha", true)
        val err = errorText()
        val card = k.card()
        card.addView(k.text("Administrador Principal", 17f, Colors.TEXT, true))
        card.addView(user, k.lp(top = 12))
        card.addView(pw, k.lp(top = 8))
        card.addView(pw2, k.lp(top = 8))
        card.addView(k.muted("Usa pelo menos 10 caracteres, com três tipos entre minúsculas, maiúsculas, números e símbolos.", 12f), k.lp(top = 8))
        card.addView(err, k.lp(top = 8))
        val btn = k.button("Criar administrador") { }
        btn.setOnClickListener {
            val u = user.text.toString()
            val p = pw.text.toString()
            when {
                AdminStore.usernameProblem(u.trim()) != null -> showError(err, AdminStore.usernameProblem(u.trim()))
                p != pw2.text.toString() -> showError(err, "As senhas não coincidem.")
                else -> {
                    showError(err, null)
                    async(btn, "A criar...", { AdminStore.setup(u, p.toCharArray()) }) { r ->
                        r.onSuccess { key ->
                            pendingKey = key
                            keyActor = u.trim()
                            pw.setText(""); pw2.setText("")
                            go(Mode.KEY_SHOW)
                        }.onFailure { showError(err, messageOf(it)) }
                    }
                }
            }
        }
        return page("Criar Administrador Principal", "Passo 1 de 2", card, btn)
    }

    private fun keyPage(): View {
        val key = pendingKey ?: ""
        val card = k.card(true)
        card.addView(k.text("Chave de recuperação", 17f, Colors.TEXT, true))
        card.addView(k.muted("Escreve esta chave em papel e guarda-a fora do telemóvel. Não será mostrada outra vez. Sem ela, se esqueceres a senha, não há maneira de recuperar o acesso.", 13f), k.lp(top = 8))
        val groups = key.split("-")
        val shown = groups.chunked(4).joinToString("\n") { it.joinToString("  ") }
        val kt = k.text(shown, 19f, Colors.GREEN, true)
        kt.typeface = android.graphics.Typeface.MONOSPACE
        kt.gravity = Gravity.CENTER
        kt.setPadding(0, k.dp(14), 0, k.dp(14))
        card.addView(kt, k.lp(top = 8))
        val confirm = k.input("Escreve os primeiros 4 caracteres da chave")
        card.addView(k.muted("Para confirmar que a guardaste, escreve aqui o primeiro grupo da chave.", 12f), k.lp(top = 4))
        card.addView(confirm, k.lp(top = 8))
        val err = errorText()
        card.addView(err, k.lp(top = 8))
        val done = k.button("Concluir") { }
        done.setOnClickListener {
            val typed = confirm.text.toString().trim().uppercase().replace("-", "").replace(" ", "")
            if (key.isEmpty() || typed != groups[0]) {
                showError(err, "Não coincide com o primeiro grupo da chave.")
            } else {
                AdminStore.acknowledgeRecovery(keyActor)
                val acc = AdminStore.accounts().firstOrNull { it.username.equals(keyActor, ignoreCase = true) }
                pendingKey = null
                if (acc != null) { AdminSession.start(acc); unlock() } else go(Mode.LOGIN)
            }
        }
        return page("Guarda a chave de recuperação", "Passo 2 de 2", card, done)
    }

    // ---------------------------------------------------------------- início de sessão

    private fun updateLockLabel() {
        val ms = AdminStore.lockRemainingMs()
        val l = lockLabel ?: return
        val b = loginButton
        if (ms > 0) {
            l.text = "Demasiadas tentativas. Tenta novamente em ${ms / 1000 + 1} s."
            l.visibility = View.VISIBLE
            b?.isEnabled = false
        } else {
            if (l.text.startsWith("Demasiadas")) l.visibility = View.GONE
            if (b != null && b.text == "Entrar") b.isEnabled = true
        }
    }

    private fun loginPage(): View {
        val user = k.input("Nome de utilizador")
        user.setText(AdminStore.lastUsername)
        val pw = k.input("Senha", true)
        val err = errorText()
        val lockL = k.text("", 13f, Colors.AMBER)
        lockL.visibility = View.GONE
        lockLabel = lockL
        val card = k.card()
        card.addView(k.text("Início de sessão", 17f, Colors.TEXT, true))
        card.addView(user, k.lp(top = 12))
        card.addView(pw, k.lp(top = 8))
        card.addView(err, k.lp(top = 8))
        card.addView(lockL, k.lp(top = 8))
        val btn = k.button("Entrar") { }
        loginButton = btn
        btn.setOnClickListener {
            val u = user.text.toString()
            val p = pw.text.toString().toCharArray()
            pw.setText("")
            showError(err, null)
            async(btn, "A verificar...", { AdminStore.login(u, p) }) { r ->
                r.onSuccess { res -> handleLogin(res, p, err) }.onFailure { showError(err, messageOf(it)) }
            }
        }
        val items = ArrayList<View>()
        items.add(card)
        items.add(btn)
        if (Build.VERSION.SDK_INT >= 29 && AdminStore.biometricAllowed() && biometricAvailable()) {
            items.add(k.button("Usar biometria", Kit.Style.SECONDARY) { promptBiometric(err) })
        }
        items.add(k.button("Esqueci a senha", Kit.Style.GHOST) { go(Mode.RECOVER) })
        val p = page("VozLivre Servidor", "Acesso do administrador", *items.toTypedArray())
        updateLockLabel()
        return p
    }

    private fun handleLogin(res: LoginResult, pw: CharArray, err: TextView) {
        when (res) {
            is LoginResult.Success -> {
                val a = res.admin
                if (a.role == Role.PRINCIPAL && !AdminStore.recoveryAcknowledged) {
                    // a chave anterior nunca foi confirmada: gera uma nova e obriga a guardá-la
                    val key = AdminStore.regenerateRecovery(a, pw)
                    if (key != null) { pendingKey = key; keyActor = a.username; go(Mode.KEY_SHOW); return }
                }
                AdminSession.start(a)
                unlock()
            }
            is LoginResult.MustChange -> { pendingAccount = res.admin; go(Mode.FIRST_CHANGE) }
            is LoginResult.Wrong -> showError(err, "Credenciais inválidas. Restam ${res.remaining} tentativas antes do bloqueio.")
            is LoginResult.Locked -> updateLockLabel()
            LoginResult.Suspended -> showError(err, "Esta conta está suspensa ou revogada.")
            LoginResult.DeviceMismatch -> showError(err, "Este telemóvel não é o dispositivo administrativo registado.")
            LoginResult.Unavailable -> showError(err, "Dados administrativos indisponíveis.")
        }
        java.util.Arrays.fill(pw, '\u0000')
    }

    @TargetApi(29)
    private fun biometricAvailable(): Boolean {
        val bm = act.getSystemService(BiometricManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    }

    @TargetApi(29)
    private fun promptBiometric(err: TextView) {
        val prompt = BiometricPrompt.Builder(act)
            .setTitle("Desbloquear VozLivre Servidor")
            .setNegativeButton("Usar senha", act.mainExecutor, DialogInterface.OnClickListener { _, _ -> })
            .build()
        prompt.authenticate(CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val acc = AdminStore.biometricLogin()
                if (acc != null) { AdminSession.start(acc); unlock() }
                else showError(err, "A biometria expirou. Usa a senha.")
            }
        })
    }

    // ---------------------------------------------------------------- troca da senha inicial

    private fun firstChangePage(): View {
        val acc = pendingAccount
        val pw = k.input("Nova senha", true)
        val pw2 = k.input("Confirmar a nova senha", true)
        val err = errorText()
        val card = k.card()
        card.addView(k.text("Define a tua senha", 17f, Colors.TEXT, true))
        card.addView(k.muted("A senha inicial foi gerada pelo administrador e só serve para o primeiro acesso.", 13f), k.lp(top = 8))
        card.addView(pw, k.lp(top = 12))
        card.addView(pw2, k.lp(top = 8))
        card.addView(err, k.lp(top = 8))
        val btn = k.button("Guardar e entrar") { }
        btn.setOnClickListener {
            val p = pw.text.toString()
            when {
                acc == null -> go(Mode.LOGIN)
                p != pw2.text.toString() -> showError(err, "As senhas não coincidem.")
                else -> async(btn, "A guardar...", { AdminStore.completeFirstChange(acc, p.toCharArray()) }) { r ->
                    r.onSuccess { AdminSession.start(acc); unlock() }.onFailure { showError(err, messageOf(it)) }
                }
            }
        }
        return page("Primeiro acesso", acc?.username ?: "", card, btn)
    }

    // ---------------------------------------------------------------- recuperação

    private fun recoverPage(): View {
        val key = k.input("Chave de recuperação")
        val pw = k.input("Nova senha", true)
        val pw2 = k.input("Confirmar a nova senha", true)
        val err = errorText()
        val card = k.card()
        card.addView(k.text("Recuperar o acesso", 17f, Colors.TEXT, true))
        card.addView(k.muted("Escreve a chave de recuperação que recebeste na configuração inicial e define uma nova senha. A identidade deste dispositivo é renovada e todas as sessões anteriores são terminadas.", 13f), k.lp(top = 8))
        card.addView(key, k.lp(top = 12))
        card.addView(pw, k.lp(top = 8))
        card.addView(pw2, k.lp(top = 8))
        card.addView(err, k.lp(top = 8))
        val btn = k.button("Recuperar") { }
        btn.setOnClickListener {
            val p = pw.text.toString()
            if (AdminStore.lockRemainingMs() > 0) {
                showError(err, "Demasiadas tentativas. Tenta novamente em ${AdminStore.lockRemainingMs() / 1000 + 1} s.")
            } else if (p != pw2.text.toString()) {
                showError(err, "As senhas não coincidem.")
            } else {
                async(btn, "A verificar...", { AdminStore.recover(key.text.toString(), p.toCharArray()) }) { r ->
                    r.onSuccess { newKey ->
                        if (newKey == null) {
                            showError(err, "Chave de recuperação inválida.")
                        } else {
                            AdminSession.end("recuperação")
                            pendingKey = newKey
                            keyActor = AdminStore.accounts().first { it.role == Role.PRINCIPAL }.username
                            go(Mode.KEY_SHOW)
                        }
                    }.onFailure { showError(err, messageOf(it)) }
                }
            }
        }
        val back = k.button("Voltar", Kit.Style.GHOST) { go(Mode.LOGIN) }
        return page("Recuperação", "Sem chave de recuperação válida não existe outra via de acesso.", card, btn, back)
    }

    private fun corruptedPage(): View {
        val card = k.card()
        card.addView(k.text("Dados administrativos inválidos", 17f, Colors.RED, true))
        card.addView(k.muted("Não foi possível abrir os dados protegidos deste servidor. Por segurança, a configuração inicial não volta a abrir. Se não houver cópia, desinstala a app e instala-a de novo; os dados e as contas deste servidor serão perdidos.", 14f), k.lp(top = 8))
        return page("VozLivre Servidor", "", card)
    }
}
