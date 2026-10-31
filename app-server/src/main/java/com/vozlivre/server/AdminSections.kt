package com.vozlivre.server

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.vozlivre.core.Colors
import com.vozlivre.core.Host
import com.vozlivre.core.Kit
import com.vozlivre.core.Section
import com.vozlivre.server.security.AdminAccount
import com.vozlivre.server.security.AdminSession
import com.vozlivre.server.security.AdminStore
import com.vozlivre.server.security.Perm
import com.vozlivre.server.security.Role
import com.vozlivre.server.security.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dialogBuilder(act: Activity): AlertDialog.Builder =
    AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)

/** Mostra um segredo (senha inicial ou chave de recuperação) uma única vez. */
private fun showSecret(act: Activity, title: String, text: String, onSaved: (() -> Unit)? = null) {
    dialogBuilder(act)
        .setTitle(title)
        .setMessage(text)
        .setCancelable(false)
        .setPositiveButton("Já guardei") { _, _ -> onSaved?.invoke() }
        .show()
}

/** Pede uma senha num diálogo e entrega-a ao chamador. */
private fun askPassword(act: Activity, title: String, onPassword: (CharArray) -> Unit) {
    val e = EditText(act)
    e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    e.setTextColor(Colors.TEXT)
    dialogBuilder(act)
        .setTitle(title)
        .setView(e)
        .setPositiveButton("Confirmar") { _, _ -> onPassword(e.text.toString().toCharArray()) }
        .setNegativeButton("Cancelar", null)
        .show()
}

private fun runAsync(act: Activity, task: () -> Unit, done: (Throwable?) -> Unit) {
    Thread {
        val err = try { task(); null } catch (e: Throwable) { e }
        act.runOnUiThread { done(err) }
    }.start()
}

/** Sessão, expiração, biometria, senha e chave de recuperação do administrador atual. */
class AdminSecuritySection(
    private val k: Kit,
    private val act: Activity,
    private val host: Host,
    private val onLock: () -> Unit
) : Section() {
    private val card = k.card()
    private val who = k.text("", 13f, Colors.TEXT, true)
    private val role = k.text("", 13f, Colors.TEXT, true)
    private val last = k.text("", 13f, Colors.TEXT, true)
    private val timeout = k.text("", 13f, Colors.TEXT, true)
    private val bioRow = k.switchRow("Desbloqueio biométrico", "Atalho válido 24 h após um início de sessão com senha.", AdminStore.biometricEnabled) { v ->
        val a = AdminSession.current()
        if (a == null) { onLock() }
        else if (!AdminSession.can(Perm.SECURITY)) { host.toast("Sem permissão para esta definição") }
        else if (AdminStore.biometricEnabled != v) AdminStore.setBiometric(a, v)
    }
    override val view: View get() = card

    @TargetApi(29)
    private fun biometricPossible(): Boolean {
        val bm = act.getSystemService(BiometricManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    }

    init {
        card.addView(k.sectionTitle(com.vozlivre.core.R.drawable.ic_shield, "Sessão administrativa"))
        card.addView(k.kv("Utilizador", who), k.lp(top = 10))
        card.addView(k.kv("Perfil", role), k.lp(top = 6))
        card.addView(k.kv("Último acesso", last), k.lp(top = 6))
        card.addView(k.kv("Expira após inatividade", timeout), k.lp(top = 6))
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(k.button("Terminar sessão", Kit.Style.DANGER, true) {
            AdminSession.end("manual")
            onLock()
        }, k.lp(0, -2, weight = 1f))
        row.addView(k.button("Alterar tempo", Kit.Style.GHOST, true) { chooseTimeout() }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 12))
        if (Build.VERSION.SDK_INT >= 29 && biometricPossible()) {
            card.addView(k.divider(), k.lp(top = 14, bottom = 14))
            card.addView(bioRow)
        }

        card.addView(k.divider(), k.lp(top = 14, bottom = 14))
        card.addView(k.text("Alterar a minha senha", 15f, Colors.TEXT, true))
        val old = k.input("Senha atual", true)
        val n1 = k.input("Nova senha", true)
        val n2 = k.input("Confirmar a nova senha", true)
        card.addView(old, k.lp(top = 8))
        card.addView(n1, k.lp(top = 8))
        card.addView(n2, k.lp(top = 8))
        val change = k.button("Alterar senha", Kit.Style.SECONDARY) { }
        change.setOnClickListener {
            val a = AdminSession.current()
            if (a == null) { onLock(); return@setOnClickListener }
            if (n1.text.toString() != n2.text.toString()) { host.toast("As senhas não coincidem"); return@setOnClickListener }
            val o = old.text.toString().toCharArray()
            val n = n1.text.toString().toCharArray()
            change.isEnabled = false
            var ok = false
            runAsync(act, { ok = AdminStore.changePassword(a, o, n) }) { err ->
                change.isEnabled = true
                if (err != null) host.toast(err.message ?: "Erro") else if (!ok) host.toast("Senha atual errada") else {
                    host.toast("Senha alterada")
                    old.setText(""); n1.setText(""); n2.setText("")
                }
            }
        }
        card.addView(change, k.lp(top = 10))

        card.addView(k.divider(), k.lp(top = 14, bottom = 14))
        card.addView(k.text("Chave de recuperação", 15f, Colors.TEXT, true))
        card.addView(k.muted("Gera uma chave nova (a anterior deixa de valer). Só o Administrador Principal pode fazê-lo.", 12f), k.lp(top = 4))
        card.addView(k.button("Gerar nova chave", Kit.Style.GHOST, true) {
            val a = AdminSession.current()
            if (a == null) onLock()
            else if (a.role != Role.PRINCIPAL) host.toast("Só o Administrador Principal pode gerar a chave")
            else askPassword(act, "Confirma a tua senha") { pw ->
                var key: String? = null
                runAsync(act, { key = AdminStore.regenerateRecovery(a, pw) }) { err ->
                    val kk = key
                    if (err != null) host.toast(err.message ?: "Erro")
                    else if (kk == null) host.toast("Senha errada")
                    else showSecret(act, "Nova chave de recuperação", "Escreve-a em papel e guarda-a fora do telemóvel. Não será mostrada outra vez.\n\n$kk") {
                        AdminStore.acknowledgeRecovery(a.username)
                    }
                }
            }
        }, k.lp(-2, -2, top = 10))
    }

    private fun chooseTimeout() {
        val a = AdminSession.current()
        if (a == null) { onLock(); return }
        if (!AdminSession.can(Perm.SECURITY)) { host.toast("Sem permissão para esta definição"); return }
        val values = intArrayOf(1, 5, 10, 30, 60)
        val labels = values.map { "$it min" }.toTypedArray()
        dialogBuilder(act)
            .setTitle("Expiração por inatividade")
            .setSingleChoiceItems(labels, values.indexOf(AdminStore.sessionTimeoutMin)) { d, which ->
                AdminStore.setSessionTimeout(a, values[which])
                d.dismiss()
                refresh()
            }
            .show()
    }

    override fun refresh() {
        val a = AdminSession.current() ?: return
        who.text = a.username
        role.text = a.role.label
        last.text = if (a.lastLogin == 0L) "-" else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(a.lastLogin))
        timeout.text = "${AdminStore.sessionTimeoutMin} min"
        val sw = bioRow.tag as Switch
        if (sw.isChecked != AdminStore.biometricEnabled) sw.isChecked = AdminStore.biometricEnabled
    }
}

/** Gestão de administradores adicionais (só o Administrador Principal). */
class AdminAccountsSection(private val k: Kit, private val act: Activity, private val host: Host) : Section() {
    private val root = LinearLayout(k.ctx)
    private val list = LinearLayout(k.ctx)
    private val name = k.input("Nome de utilizador do novo administrador")
    private var newRole = Role.OPERADOR
    private var newPerms = Perm.CALLS
    private val roleBtn = k.button("Perfil: Operador", Kit.Style.GHOST, true) { chooseRole() }
    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        list.orientation = LinearLayout.VERTICAL
        val c = k.card()
        c.addView(k.sectionTitle(com.vozlivre.core.R.drawable.ic_people, "Administradores"))
        c.addView(list, k.lp(top = 10))
        root.addView(c, k.lp(bottom = 12))

        val f = k.card()
        f.addView(k.text("Novo administrador", 15f, Colors.TEXT, true))
        f.addView(k.muted("Cada administrador tem credenciais próprias. A senha inicial é gerada aqui, mostrada uma só vez e tem de ser trocada no primeiro acesso.", 12f), k.lp(top = 4))
        f.addView(name, k.lp(top = 10))
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(roleBtn, k.lp(0, -2, weight = 1f))
        row.addView(k.button("Permissões", Kit.Style.GHOST, true) { choosePerms() }, k.lp(0, -2, left = 8, weight = 1f))
        f.addView(row, k.lp(top = 8))
        f.addView(k.button("Criar administrador", Kit.Style.SECONDARY) { create() }, k.lp(top = 10))
        root.addView(f)
    }

    private fun chooseRole() {
        val roles = arrayOf(Role.SECUNDARIO, Role.OPERADOR)
        dialogBuilder(act).setTitle("Perfil")
            .setSingleChoiceItems(roles.map { it.label }.toTypedArray(), roles.indexOf(newRole).coerceAtLeast(0)) { d, w ->
                newRole = roles[w]
                newPerms = if (newRole == Role.SECUNDARIO) Perm.ALL and Perm.MANAGE_ADMINS.inv() else Perm.CALLS
                roleBtn.text = "Perfil: " + newRole.label.removePrefix("Administrador ")
                d.dismiss()
            }.show()
    }

    private fun choosePerms() {
        val items = Perm.LABELS.filter { it.first != Perm.MANAGE_ADMINS }
        val checked = BooleanArray(items.size) { newPerms and items[it].first != 0 }
        dialogBuilder(act).setTitle("Permissões")
            .setMultiChoiceItems(items.map { it.second }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Guardar") { _, _ ->
                var p = 0
                for (i in items.indices) if (checked[i]) p = p or items[i].first
                newPerms = p
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun create() {
        val actor = AdminSession.current()
        if (actor == null || !AdminSession.can(Perm.MANAGE_ADMINS)) { host.toast("Sem permissão"); return }
        val u = name.text.toString()
        var pw: String? = null
        runAsync(act, { pw = AdminStore.createAdmin(actor, u, newRole, newPerms) }) { err ->
            val p = pw
            if (err != null) host.toast(err.message ?: "Erro")
            else if (p != null) {
                name.setText("")
                showSecret(act, "Administrador criado", "Utilizador: ${u.trim()}\nSenha inicial: $p\n\nEntrega-a em mão. Será pedida a troca no primeiro acesso e não será mostrada outra vez.")
                refresh()
            }
        }
    }

    private fun row(a: AdminAccount, actor: AdminAccount): LinearLayout {
        val r = LinearLayout(k.ctx)
        r.orientation = LinearLayout.VERTICAL
        r.background = k.shape(Colors.SURFACE, 14, Colors.STROKE, 1)
        r.setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10))
        val top = LinearLayout(k.ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(k.ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(k.text(a.username, 15f, Colors.TEXT, true))
        col.addView(k.muted(a.role.label + if (a.mustChange) " · senha inicial por trocar" else "", 12f))
        top.addView(col, k.lp(0, -2, weight = 1f))
        top.addView(k.pill(a.status.label, when (a.status) { Status.ACTIVE -> Colors.GREEN; Status.SUSPENDED -> Colors.AMBER; Status.REVOKED -> Colors.RED }))
        r.addView(top)
        if (a.role != Role.PRINCIPAL && a.status != Status.REVOKED) {
            val acts = LinearLayout(k.ctx)
            acts.orientation = LinearLayout.HORIZONTAL
            val toggle = if (a.status == Status.ACTIVE) "Suspender" else "Reativar"
            acts.addView(k.button(toggle, Kit.Style.GHOST, true) {
                runAsync(act, { AdminStore.setStatus(actor, a.id, if (a.status == Status.ACTIVE) Status.SUSPENDED else Status.ACTIVE) }) { e ->
                    if (e != null) host.toast(e.message ?: "Erro")
                    refresh()
                }
            }, k.lp(0, -2, weight = 1f))
            acts.addView(k.button("Nova senha", Kit.Style.GHOST, true) {
                var p: String? = null
                runAsync(act, { p = AdminStore.resetPassword(actor, a.id) }) { e ->
                    val pp = p
                    if (e != null) host.toast(e.message ?: "Erro")
                    else if (pp != null) showSecret(act, "Nova senha inicial", "Utilizador: ${a.username}\nSenha inicial: $pp\n\nSerá pedida a troca no primeiro acesso.")
                    refresh()
                }
            }, k.lp(0, -2, left = 6, weight = 1f))
            acts.addView(k.button("Revogar", Kit.Style.DANGER, true) {
                dialogBuilder(act).setTitle("Revogar ${a.username}?")
                    .setMessage("A conta deixa de poder entrar e não pode ser reativada.")
                    .setPositiveButton("Revogar") { _, _ ->
                        runAsync(act, { AdminStore.setStatus(actor, a.id, Status.REVOKED) }) { e ->
                            if (e != null) host.toast(e.message ?: "Erro")
                            refresh()
                        }
                    }
                    .setNegativeButton("Cancelar", null).show()
            }, k.lp(0, -2, left = 6, weight = 1f))
            r.addView(acts, k.lp(top = 8))
        }
        return r
    }

    override fun refresh() {
        list.removeAllViews()
        val actor = AdminSession.current() ?: return
        for (a in AdminStore.accounts()) list.addView(row(a, actor), k.lp(bottom = 8))
    }
}

/** Registo de auditoria com verificação da cadeia. */
class AuditSection(private val k: Kit, private val host: Host) : Section() {
    private val card = k.card()
    private val body = k.mono("", 10f)
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(com.vozlivre.core.R.drawable.ic_doc, "Auditoria administrativa"))
        card.addView(k.muted("Acessos, tentativas falhadas, recuperação e alterações de contas. Cada entrada é selada com uma chave do Android Keystore.", 12f), k.lp(top = 6))
        card.addView(k.button("Verificar integridade", Kit.Style.SECONDARY, true) {
            val bad = AdminStore.audit.verify()
            host.toast(if (bad == null) "Cadeia íntegra" else "Entrada $bad foi alterada ou falta")
        }, k.lp(-2, -2, top = 10))
        card.addView(body, k.lp(top = 10))
    }

    override fun refresh() {
        if (!AdminSession.can(Perm.AUDIT)) { body.text = "Sem permissão."; return }
        val items = AdminStore.audit.list(80)
        body.text = if (items.isEmpty()) "Sem entradas." else items.joinToString("\n") {
            "${fmt.format(Date(it.ts))}  ${it.type}  ${it.actor}" + if (it.detail.isNotEmpty()) "  ${it.detail}" else ""
        }
    }
}
