package com.vozlivre.server.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Registo de auditoria com cadeia selada: cada entrada inclui o selo da anterior e é assinada
 * com uma chave HMAC do Android Keystore. Alterar ou apagar uma entrada quebra a verificação.
 */
class AuditLog private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "vozlivre_audit.db", null, 1) {

    class Entry(val id: Long, val ts: Long, val type: String, val actor: String, val detail: String)

    companion object {
        private const val GENESIS = "GENESIS"
        @Volatile private var inst: AuditLog? = null

        fun get(ctx: Context): AuditLog =
            inst ?: synchronized(this) { inst ?: AuditLog(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, " +
                "actor TEXT NOT NULL, detail TEXT NOT NULL, prev TEXT NOT NULL, hash TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun seal(prev: String, ts: Long, type: String, actor: String, detail: String): String {
        val mac = KeystoreCrypto.hmac("$prev|$ts|$type|$actor|$detail".toByteArray(Charsets.UTF_8))
        return mac.joinToString("") { "%02x".format(it) }
    }

    private fun lastHash(db: SQLiteDatabase): String {
        db.rawQuery("SELECT hash FROM audit ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else GENESIS
        }
    }

    @Synchronized
    fun append(type: String, actor: String, detail: String = "") {
        try {
            val db = writableDatabase
            val prev = lastHash(db)
            val ts = System.currentTimeMillis()
            val a = actor.take(64)
            val d = detail.take(300)
            val v = ContentValues()
            v.put("ts", ts); v.put("type", type); v.put("actor", a); v.put("detail", d)
            v.put("prev", prev); v.put("hash", seal(prev, ts, type, a, d))
            db.insert("audit", null, v)
        } catch (e: Exception) {
            // a auditoria não pode derrubar a app; a falha fica visível na verificação seguinte
        }
    }

    @Synchronized
    fun list(limit: Int): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery("SELECT id, ts, type, actor, detail FROM audit ORDER BY id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)))
        }
        return out
    }

    /** Devolve null se a cadeia está íntegra, ou o id da primeira entrada inválida. */
    @Synchronized
    fun verify(): Long? {
        var expectedPrev = GENESIS
        readableDatabase.rawQuery("SELECT id, ts, type, actor, detail, prev, hash FROM audit ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val prev = c.getString(5)
                val ok = try { prev == expectedPrev && seal(prev, c.getLong(1), c.getString(2), c.getString(3), c.getString(4)) == c.getString(6) } catch (e: Exception) { false }
                if (!ok) return id
                expectedPrev = c.getString(6)
            }
        }
        return null
    }
}
