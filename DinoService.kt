package com.pinoy.dino

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Face indexes (0-based) on the 5x5 "25 Expressions" sheet:
 * 0 Happy 3 Sad 4 Excited 7 Sleepy 8 Thinking 9 Pouty 10 Love 17 Tired 18 Cool
 * 19 Angry2 22 Confused 23 Begging 24 Goodbye
 */
class DinoService : Service(), TextToSpeech.OnInitListener {
    private lateinit var wm: WindowManager
    private lateinit var brain: Brain
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var face: ImageView
    private lateinit var bar: LinearLayout
    private lateinit var muteBtn: Button
    private lateinit var lp: WindowManager.LayoutParams
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var sheet: Bitmap? = null
    private val cache = HashMap<Int, Bitmap>()
    private val h = Handler(Looper.getMainLooper())
    private val muteH = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null

    private var muted = false
    private var asleep = false
    private var muteStage = 0
    private var sulkUntil = 0L
    private var alarmStart = 0L
    private var lastTouch = System.currentTimeMillis()
    private var ticks = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        brain = Brain(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // The sheet is decoded once (~6 MB RAM) and each face is cropped on demand.
        sheet = try { assets.open("expressions.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        buildUi()
        tts = TextToSpeech(this, this)
        h.postDelayed(tick, 30_000)
    }

    private fun startAsForeground() {
        val ch = "dino"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(ch, "Pinoy Dino", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch)
            .setContentTitle("Pinoy Dino is on your screen")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)
    }

    private fun overlay(w: Int, hgt: Int, g: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, hgt, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = g; this.x = x; this.y = y }

    private fun buildUi() {
        bubble = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 14f; setPadding(dp(10), dp(6), dp(10), dp(6))
            maxWidth = dp(220); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        }
        face = ImageView(this).apply { setImageBitmap(face(0)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble); addView(face)
        }
        lp = overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START, 0, dp(60))
        wm.addView(root, lp)

        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        face.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lastTouch = System.currentTimeMillis(); dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val ox = (e.rawX - dx).toInt(); val oy = (e.rawY - dy).toInt()
                    if (abs(ox) + abs(oy) > 15) moved = true
                    if (moved) { lp.x = sx + ox; lp.y = sy - oy; wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }

        // Mute bar (separate window, bottom centre) - always tappable.
        muteBtn = Button(this).apply { text = "\uD83D\uDD0A Mute"; setOnClickListener { toggleMute() } }
        bar = LinearLayout(this).apply { addView(muteBtn) }
        wm.addView(bar, overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0))
    }

    /** Crop one expression out of the 5x5 sheet. */
    private fun face(i: Int): Bitmap = cache.getOrPut(i) {
        val s = sheet ?: return@getOrPut Bitmap.createBitmap(dp(150), dp(117), Bitmap.Config.ARGB_8888)
        val fx = s.width / 1312f; val fy = s.height / 1199f
        val tops = intArrayOf(135, 350, 550, 745, 925)
        val x = ((i % 5) * 262.4f * fx).toInt().coerceIn(0, s.width - 2)
        val y = (tops[i / 5] * fy).toInt().coerceIn(0, s.height - 2)
        val w = minOf((262 * fx).toInt(), s.width - x)
        val hh = minOf((205 * fy).toInt(), s.height - y)
        Bitmap.createScaledBitmap(Bitmap.createBitmap(s, x, y, w, hh), dp(150), dp(117), true)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val fil = Locale("fil", "PH")
        t.language = if (t.isLanguageAvailable(fil) >= TextToSpeech.LANG_AVAILABLE) fil else Locale.US
        t.setPitch(1.3f); t.setSpeechRate(1.05f)
        ttsOk = true
        say("Uy! Andito na ako sa screen mo!", 4)
    }

    /** Show a bubble + face, and speak unless muted/asleep. */
    private fun say(text: String, f: Int) {
        val clean = sanitize(text)
        face.setImageBitmap(face(f))
        bubble.text = clean; bubble.visibility = View.VISIBLE
        h.removeCallbacks(hideBubble); h.postDelayed(hideBubble, 7000)
        if (!muted && !asleep && ttsOk) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "d")
    }

    private val hideBubble = Runnable { if (!asleep && muteStage == 0) { bubble.visibility = View.GONE; face.setImageBitmap(face(0)) } }

    private fun onTap() {
        lastTouch = System.currentTimeMillis()
        if (alarmStart != 0L) { alarmStart = 0; say("Sige, gising ka na! Kain na tayo!", 0); return }
        if (asleep) { say("Zzz... mamaya na...", 7); return }
        if (sulkUntil != 0L) {
            if (System.currentTimeMillis() < sulkUntil) { face.setImageBitmap(face(9)); bubble.text = "..."; bubble.visibility = View.VISIBLE }
            else { sulkUntil = 0; muteStage = 0; say("Sige na nga, bati na tayo. Sorry din.", 10) }
            return
        }
        listen()
    }

    // ---- Voice in ----
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Pa-allow ng mic sa app, bes!", 22); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("Walang speech service dito.", 22); return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle) {
                    val t = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (t.isBlank()) say("Ha? Ulitin mo nga.", 22) else say(brain.reply(t), 0)
                }
                override fun onError(e: Int) { say("Hindi kita narinig, tap mo ulit ako.", 22) }
                override fun onReadyForSpeech(p: Bundle?) { say("Nakikinig ako...", 8) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fil-PH")
            })
        }
    }

    // ---- Mute drama (5 stages) ----
    private fun toggleMute() {
        lastTouch = System.currentTimeMillis()
        muted = !muted
        muteBtn.text = if (muted) "\uD83D\uDD07 Unmute" else "\uD83D\uDD0A Mute"
        tts?.stop()
        if (muted) {
            muteStage = 0
            val steps = listOf(
                0L to (22 to "Huh? Bakit ang tahimik?"),
                4000L to (23 to "Please, wag mo akong i-mute! Behave ako, promise!"),
                10000L to (3 to "Ang sama mo! Wala ka nang pakialam sa akin!"),
                18000L to (19 to "Hmp! Bahala ka! *stomp stomp*"),
                26000L to (9 to "...")
            )
            steps.forEachIndexed { i, (t, p) ->
                muteH.postDelayed({ muteStage = i + 1; say(p.second, p.first) }, t)
            }
        } else {
            muteH.removeCallbacksAndMessages(null)
            if (muteStage >= 4) {
                sulkUntil = System.currentTimeMillis() + 10_000   // silent for 10 s, then tap to apologize
                face.setImageBitmap(face(9)); bubble.text = "..."; bubble.visibility = View.VISIBLE
            } else { muteStage = 0; say("Yey! Salamat, hindi mo ako iniwan!", 0) }
        }
    }

    // ---- Life cycle: sleep 22:00, wake 05:00 with alarm, boredom, random hugot ----
    private val tick = object : Runnable {
        override fun run() {
            val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val night = hr >= 22 || hr < 5
            if (night && !asleep) goSleep() else if (!night && asleep) wakeUp()
            ticks++
            val idle = System.currentTimeMillis() - lastTouch
            if (!asleep && !muted && sulkUntil == 0L && alarmStart == 0L) {
                if (idle > 15 * 60_000) { lastTouch = System.currentTimeMillis(); bored() }
                else if (ticks % 10 == 0) say(brain.hugot(), 0)
            }
            h.postDelayed(this, 30_000)
        }
    }

    private fun goSleep() {
        asleep = true; tts?.stop()
        face.setImageBitmap(face(7)); root.alpha = 0.5f
        bubble.text = "Zzz..."; bubble.visibility = View.VISIBLE
    }

    private fun wakeUp() {
        asleep = false; root.alpha = 1f; alarmStart = System.currentTimeMillis()
        h.post(alarmTick)
    }

    private val alarmTick = object : Runnable {
        override fun run() {
            if (alarmStart == 0L) return
            say(brain.wake, 4)
            if (System.currentTimeMillis() - alarmStart > 3 * 60_000) shake()
            h.postDelayed(this, 20_000)
        }
    }

    private fun shake() {
        val base = lp.x
        ValueAnimator.ofInt(0, 40, -40, 0).apply {
            duration = 400; repeatCount = 3
            addUpdateListener { lp.x = base + it.animatedValue as Int; wm.updateViewLayout(root, lp) }
            start()
        }
    }

    /** Boredom: grabs a snippet of your notes, crumples it into a ball, throws it across the screen. */
    private fun bored() {
        say("Ang boring! Kunin ko nga tong notes mo, gawing bola, tapos ihagis!", 9)
        val snippet = (brain.db.random() ?: "boring boring boring").take(14)
        val ball = TextView(this).apply {
            text = "\uD83D\uDCC4 $snippet"; textSize = 12f; setTextColor(Color.BLACK); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFF3B0")); cornerRadius = dp(20).toFloat() }
        }
        val dm = resources.displayMetrics
        val p = overlay(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START, lp.x + dp(100), dm.heightPixels - lp.y - dp(200))
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.addView(ball, p)
        val x0 = p.x; val y0 = p.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            addUpdateListener {
                val t = it.animatedValue as Float
                p.x = x0 + ((dm.widthPixels - x0) * t).toInt()
                p.y = y0 - (4 * dp(220) * t * (1 - t)).toInt() + (dp(120) * t).toInt()   // parabola + gravity
                wm.updateViewLayout(ball, p)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    ball.text = "\uD83D\uDCA5"; ball.setBackgroundColor(Color.TRANSPARENT); ball.textSize = 30f
                    h.postDelayed({ try { wm.removeView(ball) } catch (_: Exception) {} }, 400)
                }
            })
            start()
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); muteH.removeCallbacksAndMessages(null)
        try { wm.removeView(root); wm.removeView(bar) } catch (_: Exception) {}
        rec?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
