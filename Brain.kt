package com.pinoy.dino

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Hard storage cap: everything the app stores (notes DB + files) stays under 3 GB. */
object Guard {
    const val MAX_BYTES = 3L * 1024 * 1024 * 1024
    fun used(c: Context): Long =
        c.filesDir.walk().filter { it.isFile }.sumOf { it.length() } +
            (c.getDatabasePath("notes.db").takeIf { it.exists() }?.length() ?: 0L)

    fun hasRoom(c: Context, extra: Long) = used(c) + extra <= MAX_BYTES
}

/** Removes the word "yarn" before anything is shown or spoken. */
fun sanitize(t: String) =
    t.replace(Regex("\\byarn\\b", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()

/** Light local knowledge base: chunked notes in SQLite, keyword-scored. Needs only a few MB of RAM. */
class NotesDb(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, src TEXT, body TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun addText(src: String, text: String, size: Int = 700, overlap: Int = 100): Int {
        val db = writableDatabase
        var i = 0
        var n = 0
        db.beginTransaction()
        try {
            while (i < text.length) {
                val end = minOf(text.length, i + size)
                val v = ContentValues().apply { put("src", src); put("body", text.substring(i, end)) }
                db.insert("notes", null, v)
                n++
                if (end == text.length) break
                i = end - overlap
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null)
        .use { it.moveToFirst(); it.getInt(0) }

    fun random(): String? = readableDatabase
        .rawQuery("SELECT body FROM notes ORDER BY RANDOM() LIMIT 1", null)
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun search(q: String): String? {
        val words = q.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return null
        val where = words.joinToString(" OR ") { "body LIKE ?" }
        var best: String? = null
        var bestScore = 0
        readableDatabase.rawQuery(
            "SELECT body FROM notes WHERE $where LIMIT 60",
            words.map { "%$it%" }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val b = c.getString(0)
                val l = b.lowercase()
                val s = words.count { l.contains(it) }
                if (s > bestScore) { bestScore = s; best = b }
            }
        }
        return best?.replace(Regex("\\s+"), " ")?.take(220)
    }
}

class Brain(ctx: Context) {
    val db = NotesDb(ctx)
    private val hugot = listOf(
        "Sana all may jowa. Ako, hanggang drawing lang.",
        "Wag puro phone, baka mata mo maging parang screen!",
        "Kumain ka na ba? Hindi pwedeng gutom ang masipag.",
        "Ikaw ba si WiFi? Kasi ang lakas ng koneksyon natin.",
        "Uy, inom ka muna ng tubig. Hydrate, bes!"
    )
    val wake = "Gising na! Bangon! Alas singko na! Mag-ehersisyo tayo!"
    fun hugot() = hugot.random()

    fun reply(input: String): String {
        val t = input.lowercase()
        return when {
            Regex("\\b(hello|hi|hey|kumusta|uy)\\b").containsMatchIn(t) -> "Uy! Kumusta ka? Ako, gutom na naman."
            "pangalan" in t || "name" in t -> "Ako si Pinoy Dino, ang pinaka-gwapong dinosaur sa screen mo!"
            "kanta" in t || "sing" in t -> "La la la... off-key pero may puso!"
            "hugot" in t -> hugot()
            else -> db.search(input)?.let { "Sabi sa notes mo: $it" } ?: hugot()
        }
    }
}
