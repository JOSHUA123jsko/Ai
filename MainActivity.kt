package com.pinoy.dino

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var info: TextView
    private val PICK = 7

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply { textSize = 14f }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
            addView(TextView(this@MainActivity).apply { text = "Pinoy Dino"; textSize = 26f })
            addView(btn("1. Allow draw-over-apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(btn("2. Allow microphone / notifications") {
                val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(p.toTypedArray(), 1)
            })
            addView(btn("3. Start Dino") {
                if (!Settings.canDrawOverlays(this@MainActivity)) toast("Allow draw-over-apps first")
                else startForegroundService(Intent(this@MainActivity, DinoService::class.java))
            })
            addView(btn("Stop Dino") { stopService(Intent(this@MainActivity, DinoService::class.java)) })
            addView(btn("Import notes (.txt / .md)") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown"))
                }, PICK)
            })
            addView(info)
        }
        setContentView(col)
        refresh()
    }

    private fun refresh() {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val usedMb = Guard.used(this) / 1_048_576
        info.text = "Storage used: $usedMb MB of 3072 MB\nNote chunks: ${Brain(this).db.count()}\n" +
            "RAM total: ${mi.totalMem / 1_048_576} MB (app needs ~150 MB)\n\n" +
            "Tap the dino to talk. Drag to move. Use the bottom Mute bar for the drama."
    }

    @Deprecated("simple picker")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != PICK || res != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris += it.getItemAt(i).uri }
        data.data?.let { uris += it }
        Thread {
            val db = Brain(this).db
            var chunks = 0
            for (u in uris) {
                try {
                    val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                    if (bytes.size > 5_000_000 || !Guard.hasRoom(this, bytes.size * 2L)) continue
                    chunks += db.addText(u.lastPathSegment ?: "note", String(bytes))
                } catch (_: Exception) {}
            }
            runOnUiThread { toast("Added $chunks chunks"); refresh() }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
