package com.vozlivre.server.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Role(val label: String) { PRINCIPAL("Administrador Principal"), SECUNDARIO("Administrador Secundário"), OPERADOR("Operador") }
enum class Status(val label: String) { ACTIVE("Ativo"), SUSPENDED("Suspenso"), REVOKED("Revogado") }

/** Permissões individuais de cada administrador. */
object Perm {
    const val MANAGE_ADMINS = 1
    const val SECURITY = 2
    const val CLIENTS = 4
    const val NETWORK = 8
    const val AUDIT = 16
    const val CALLS = 32
    const val ALL = 63
    val LABELS = listOf(
        MANAGE_ADMINS to "Gerir administradores", SECURITY to "Definições de segurança", CLIENTS to "Gerir clientes",
        NETWORK to "Gerir a rede", AUDIT to "Ver auditoria", CALLS to "Operar chamadas"
    )
}

class AdminAccount(
    val id: String,
    var username: String,
    var role: Role,
    var status: Status,
    var salt: ByteArray,
    var hash: ByteArray,
    var iterations: Int,
    val createdAt: Long,
    var lastLogin: Long,
    var mustChange: Boolean,
    var devicePub: ByteArray?,
    var perms: Int
)

sealed class LoginResult {
    class Success(val admin: AdminAccount) : LoginResult()
    class MustChange(val admin: AdminAccount) : LoginResult()
    class Wrong(val remaining: Int) : LoginResult()
    class Locked(val until: Long) : LoginResult()
    object Suspended : LoginResult()
    object DeviceMismatch : LoginResult()
    object Unavailable : LoginResult()
}

/**
 * Estado administrativo do Servidor: contas, recuperação, definições e proteção contra tentativas.
 * Fica numa única estrutura cifrada com AES-256-GCM (chave no Android Keystore).
 * Se o estado não puder ser decifrado, a configuração inicial NÃO volta a abrir: isso permitiria
 * a alguém tomar o servidor apagando a chave. A app fica bloqueada em "dados inválidos".
 */
object AdminStore {
    private const val MAX_FREE_ATTEMPTS = 5
    private val DUMMY_SALT = ByteArray(16)

    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private val admins = ArrayList<AdminAccount>()
    private var recoverySalt: ByteArray = ByteArray(0)
    private var recoveryHash: ByteArray = ByteArray(0)
    private var recoveryAck = false
    private var loaded = false

    @Volatile var corrupted = false; private set
    @Volatile var epoch = 0; private set
    @Volatile var sessionTimeoutMin = 10; private set
    @Volatile var biometricEnabled = false; private set
    @Volatile var lastPasswordLogin = 0L; private set
    @Volatile var lastUsername = ""; private set
    private var failed = 0
    private var lockUntil = 0L

    val initialized: Boolean get() = loaded
    val setupAllowed: Boolean get() = !loaded && !corrupted && !prefs.contains("state")
    val recoveryAcknowledged: Boolean get() = recoveryAck
    val audit: AuditLog get() = AuditLog.get(appCtx)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("vozlivre_admin", Context.MODE_PRIVATE)
        load()
    }

    // ---------------------------------------------------------------- persistência

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun toJson(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("epoch", epoch)
        o.put("timeoutMin", sessionTimeoutMin)
        o.put("biometric", biometricEnabled)
        o.put("failed", failed)
        o.put("lockUntil", lockUntil)
        o.put("lastPasswordLogin", lastPasswordLogin)
        o.put("lastUsername", lastUsername)
        o.put("recoverySalt", b64(recoverySalt))
        o.put("recoveryHash", b64(recoveryHash))
        o.put("recoveryAck", recoveryAck)
        val arr = JSONArray()
        for (a in admins) {
            val j = JSONObject()
            j.put("id", a.id); j.put("username", a.username); j.put("role", a.role.name); j.put("status", a.status.name)
            j.put("salt", b64(a.salt)); j.put("hash", b64(a.hash)); j.put("iter", a.iterations)
            j.put("created", a.createdAt); j.put("lastLogin", a.lastLogin); j.put("mustChange", a.mustChange)
            j.put("devicePub", a.devicePub?.let { b64(it) } ?: ""); j.put("perms", a.perms)
            arr.put(j)
        }
        o.put("admins", arr)
        return o.toString()
    }

    private fun load() {
        val raw = prefs.getString("state", null)
        if (raw == null) { loaded = false; corrupted = false; return }
        try {
            val json = JSONObject(String(KeystoreCrypto.decrypt(unb64(raw)), Charsets.UTF_8))
            epoch = json.getInt("epoch")
            sessionTimeoutMin = json.getInt("timeoutMin")
            biometricEnabled = json.getBoolean("biometric")
            failed = json.getInt("failed")
            lockUntil = json.getLong("lockUntil")
            lastPasswordLogin = json.optLong("lastPasswordLogin", 0L)
            lastUsername = json.optString("lastUsername", "")
            recoverySalt = unb64(json.getString("recoverySalt"))
            recoveryHash = unb64(json.getString("recoveryHash"))
            recoveryAck = json.getBoolean("recoveryAck")
            admins.clear()
            val arr = json.getJSONArray("admins")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val pub = j.getString("devicePub")
                admins.add(
                    AdminAccount(
                        j.getString("id"), j.getString("username"), Role.valueOf(j.getString("role")), Status.valueOf(j.getString("status")),
                        unb64(j.getString("salt")), unb64(j.getString("hash")), j.getInt("iter"), j.getLong("created"),
                        j.getLong("lastLogin"), j.getBoolean("mustChange"), if (pub.isEmpty()) null else unb64(pub), j.getInt("perms")
                    )
                )
            }
            loaded = true
            corrupted = false
        } catch (e: Exception) {
            loaded = false
            corrupted = true
        }
    }

    private fun save() {
        val enc = KeystoreCrypto.encrypt(toJson().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("state", b64(enc)).commit()
        loaded = true
    }

    fun auditEvent(type: String, actor: String, detail: String = "") { audit.append(type, actor, detail) }

    // ---------------------------------------------------------------- validação

    fun usernameProblem(u: String): String? {
        if (u.length < 3 || u.length > 32) return "O nome de utilizador deve ter entre 3 e 32 caracteres."
        if (!u.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) return "Usa só letras, números, ponto, hífen e sublinhado."
        return null
    }

    fun accounts(): List<AdminAccount> = admins.toList()
    fun find(id: String): AdminAccount? = admins.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- configuração inicial

    /** Cria o Administrador Principal. Só funciona uma vez. Devolve a chave de recuperação (mostrada uma única vez). */
    @Synchronized
    fun setup(username: String, password: CharArray): String {
        check(setupAllowed) { "A configuração inicial já foi feita." }
        val u = username.trim()
        usernameProblem(u)?.let { throw IllegalArgumentException(it) }
        PasswordHasher.strengthProblem(String(password), u)?.let { throw IllegalArgumentException(it) }
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash(password, salt)
        val pub = DeviceIdentity.create()
        val key = RecoveryKey.generate()
        recoverySalt = PasswordHasher.newSalt()
        recoveryHash = PasswordHasher.hash(RecoveryKey.normalize(key).toCharArray(), recoverySalt)
        recoveryAck = false
        admins.clear()
        admins.add(AdminAccount(UUID.randomUUID().toString(), u, Role.PRINCIPAL, Status.ACTIVE, salt, hash, PasswordHasher.ITERATIONS, System.currentTimeMillis(), 0L, false, pub, Perm.ALL))
        epoch = 0; failed = 0; lockUntil = 0L
        save()
        audit.append("SETUP", u, "Administrador Principal criado e dispositivo registado")
        return key
    }

    @Synchronized
    fun acknowledgeRecovery(actor: String) {
        if (recoveryAck) return
        recoveryAck = true
        save()
        audit.append("RECOVERY_KEY_CONFIRMED", actor, "Chave de recuperação confirmada pelo utilizador")
    }

    // ---------------------------------------------------------------- tentativas e bloqueio

    fun lockRemainingMs(): Long = maxOf(0L, lockUntil - System.currentTimeMillis())

    private fun registerFailure(actor: String, what: String): Int {
        failed++
        if (failed >= MAX_FREE_ATTEMPTS) {
            val steps = minOf(failed - MAX_FREE_ATTEMPTS, 5)
            val wait = minOf(15L * 60_000L, 30_000L shl steps)
            lockUntil = System.currentTimeMillis() + wait
            audit.append("LOCKOUT", actor, "$what: bloqueado ${wait / 1000} s após $failed tentativas falhadas")
        } else {
            audit.append("LOGIN_FAIL", actor, what)
        }
        save()
        return maxOf(0, MAX_FREE_ATTEMPTS - failed)
    }

    private fun clearFailures() { failed = 0; lockUntil = 0L }

    // ---------------------------------------------------------------- autenticação

    @Synchronized
    fun login(username: String, password: CharArray): LoginResult {
        if (!loaded) return LoginResult.Unavailable
        val now = System.currentTimeMillis()
        if (lockUntil > now) return LoginResult.Locked(lockUntil)
        val u = username.trim()
        val acc = admins.firstOrNull { it.username.equals(u, ignoreCase = true) }
        // calcula sempre o hash, mesmo para nomes inexistentes, para não revelar que o nome existe
        val computed = PasswordHasher.hash(password, acc?.salt ?: DUMMY_SALT, acc?.iterations ?: PasswordHasher.ITERATIONS)
        if (acc == null || !PasswordHasher.equal(computed, acc.hash)) {
            val left = registerFailure(u.take(32), "Credenciais inválidas")
            return if (lockUntil > System.currentTimeMillis()) LoginResult.Locked(lockUntil) else LoginResult.Wrong(left)
        }
        if (acc.status != Status.ACTIVE) {
            audit.append("LOGIN_BLOCKED", acc.username, "Conta ${acc.status.label.lowercase()}")
            return LoginResult.Suspended
        }
        val pub = acc.devicePub
        if (pub != null && !DeviceIdentity.proves(pub)) {
            audit.append("LOGIN_DEVICE_MISMATCH", acc.username, "Este dispositivo não possui a identidade registada")
            return LoginResult.DeviceMismatch
        }
        clearFailures()
        acc.lastLogin = now
        lastPasswordLogin = now
        lastUsername = acc.username
        save()
        audit.append("LOGIN_OK", acc.username, "Início de sessão")
        return if (acc.mustChange) LoginResult.MustChange(acc) else LoginResult.Success(acc)
    }

    /** Biometria: só vale como atalho nas 24 h seguintes a um início de sessão com senha. */
    fun biometricAllowed(): Boolean =
        biometricEnabled && loaded && lastUsername.isNotEmpty() && System.currentTimeMillis() - lastPasswordLogin < 24L * 3600_000L

    @Synchronized
    fun biometricLogin(): AdminAccount? {
        if (!biometricAllowed()) return null
        val acc = admins.firstOrNull { it.username.equals(lastUsername, ignoreCase = true) } ?: return null
        if (acc.status != Status.ACTIVE || acc.mustChange) return null
        val pub = acc.devicePub
        if (pub != null && !DeviceIdentity.proves(pub)) return null
        audit.append("LOGIN_BIOMETRIC", acc.username, "Desbloqueio biométrico")
        return acc
    }

    // ---------------------------------------------------------------- senhas

    @Synchronized
    fun completeFirstChange(acc: AdminAccount, newPassword: CharArray) {
        PasswordHasher.strengthProblem(String(newPassword), acc.username)?.let { throw IllegalArgumentException(it) }
        acc.salt = PasswordHasher.newSalt()
        acc.hash = PasswordHasher.hash(newPassword, acc.salt)
        acc.iterations = PasswordHasher.ITERATIONS
        acc.mustChange = false
        save()
        audit.append("PASSWORD_CHANGED", acc.username, "Senha inicial substituída")
    }

    @Synchronized
    fun changePassword(acc: AdminAccount, old: CharArray, new: CharArray): Boolean {
        val computed = PasswordHasher.hash(old, acc.salt, acc.iterations)
        if (!PasswordHasher.equal(computed, acc.hash)) {
            registerFailure(acc.username, "Senha atual errada ao alterar senha")
            return false
        }
        PasswordHasher.strengthProblem(String(new), acc.username)?.let { throw IllegalArgumentException(it) }
        acc.salt = PasswordHasher.newSalt()
        acc.hash = PasswordHasher.hash(new, acc.salt)
        acc.iterations = PasswordHasher.ITERATIONS
        save()
        audit.append("PASSWORD_CHANGED", acc.username, "Senha alterada pelo próprio")
        return true
    }

    // ---------------------------------------------------------------- recuperação

    /**
     * Recupera o acesso do Administrador Principal com a chave de recuperação.
     * Define nova senha, gera nova identidade do dispositivo (a antiga deixa de valer), revoga todas as
     * sessões e devolve uma nova chave de recuperação. Devolve null se a chave estiver errada.
     */
    @Synchronized
    fun recover(recoveryKey: String, newPassword: CharArray): String? {
        if (!loaded) return null
        if (lockUntil > System.currentTimeMillis()) return null
        val normalized = RecoveryKey.normalize(recoveryKey)
        val computed = PasswordHasher.hash(normalized.toCharArray(), recoverySalt)
        if (normalized.length != 32 || !PasswordHasher.equal(computed, recoveryHash)) {
            registerFailure("recuperação", "Chave de recuperação inválida")
            return null
        }
        val p = admins.first { it.role == Role.PRINCIPAL }
        PasswordHasher.strengthProblem(String(newPassword), p.username)?.let { throw IllegalArgumentException(it) }
        p.salt = PasswordHasher.newSalt()
        p.hash = PasswordHasher.hash(newPassword, p.salt)
        p.iterations = PasswordHasher.ITERATIONS
        p.status = Status.ACTIVE
        p.mustChange = false
        val pub = DeviceIdentity.create()
        for (a in admins) a.devicePub = pub
        val newKey = RecoveryKey.generate()
        recoverySalt = PasswordHasher.newSalt()
        recoveryHash = PasswordHasher.hash(RecoveryKey.normalize(newKey).toCharArray(), recoverySalt)
        recoveryAck = false
        epoch++
        clearFailures()
        save()
        audit.append("RECOVERY", p.username, "Acesso recuperado, dispositivo re-registado e sessões anteriores revogadas")
        return newKey
    }

    /** Gera nova chave de recuperação, exigindo a senha atual. */
    @Synchronized
    fun regenerateRecovery(acc: AdminAccount, password: CharArray): String? {
        if (acc.role != Role.PRINCIPAL) return null
        val computed = PasswordHasher.hash(password, acc.salt, acc.iterations)
        if (!PasswordHasher.equal(computed, acc.hash)) {
            registerFailure(acc.username, "Senha errada ao gerar chave de recuperação")
            return null
        }
        val key = RecoveryKey.generate()
        recoverySalt = PasswordHasher.newSalt()
        recoveryHash = PasswordHasher.hash(RecoveryKey.normalize(key).toCharArray(), recoverySalt)
        recoveryAck = false
        save()
        audit.append("RECOVERY_KEY_REGENERATED", acc.username, "Nova chave de recuperação gerada")
        return key
    }

    // ---------------------------------------------------------------- definições

    @Synchronized
    fun setSessionTimeout(actor: AdminAccount, minutes: Int) {
        sessionTimeoutMin = minutes
        save()
        audit.append("SETTING_SESSION_TIMEOUT", actor.username, "$minutes min")
    }

    @Synchronized
    fun setBiometric(actor: AdminAccount, on: Boolean) {
        biometricEnabled = on
        save()
        audit.append("SETTING_BIOMETRIC", actor.username, if (on) "ativada" else "desativada")
    }

    // ---------------------------------------------------------------- administradores adicionais

    private fun requireManager(actor: AdminAccount) {
        require(actor.role == Role.PRINCIPAL && actor.perms and Perm.MANAGE_ADMINS != 0) { "Só o Administrador Principal pode gerir administradores." }
    }

    /** Cria um administrador adicional e devolve a senha inicial (mostrada uma única vez, a trocar no primeiro acesso). */
    @Synchronized
    fun createAdmin(actor: AdminAccount, username: String, role: Role, perms: Int): String {
        requireManager(actor)
        require(role != Role.PRINCIPAL) { "Só existe um Administrador Principal." }
        val u = username.trim()
        usernameProblem(u)?.let { throw IllegalArgumentException(it) }
        require(admins.none { it.username.equals(u, ignoreCase = true) }) { "Esse nome de utilizador já existe." }
        val initial = PasswordHasher.randomPassword()
        val salt = PasswordHasher.newSalt()
        val granted = perms and Perm.ALL and Perm.MANAGE_ADMINS.inv()
        admins.add(AdminAccount(UUID.randomUUID().toString(), u, role, Status.ACTIVE, salt, PasswordHasher.hash(initial.toCharArray(), salt), PasswordHasher.ITERATIONS, System.currentTimeMillis(), 0L, true, DeviceIdentity.publicKey(), granted))
        save()
        audit.append("ADMIN_CREATED", actor.username, "utilizador=$u perfil=${role.name} permissões=$granted")
        return initial
    }

    @Synchronized
    fun setStatus(actor: AdminAccount, targetId: String, status: Status) {
        requireManager(actor)
        val t = admins.firstOrNull { it.id == targetId } ?: return
        require(t.role != Role.PRINCIPAL) { "O Administrador Principal não pode ser alterado." }
        require(t.status != Status.REVOKED) { "Uma conta revogada não pode ser reativada." }
        t.status = status
        epoch++ // sessões em curso têm de ser revalidadas
        save()
        audit.append("ADMIN_STATUS", actor.username, "utilizador=${t.username} estado=${status.name}")
    }

    @Synchronized
    fun resetPassword(actor: AdminAccount, targetId: String): String? {
        requireManager(actor)
        val t = admins.firstOrNull { it.id == targetId } ?: return null
        require(t.role != Role.PRINCIPAL) { "O Administrador Principal não pode ser alterado." }
        val initial = PasswordHasher.randomPassword()
        t.salt = PasswordHasher.newSalt()
        t.hash = PasswordHasher.hash(initial.toCharArray(), t.salt)
        t.iterations = PasswordHasher.ITERATIONS
        t.mustChange = true
        epoch++
        save()
        audit.append("ADMIN_PASSWORD_RESET", actor.username, "utilizador=${t.username}")
        return initial
    }
}
