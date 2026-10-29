package com.past9.phoneaos.wakeup

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The ringing screen: shown over the lock screen and turns the screen on. The severity colour edge to edge, the
 * title, exactly what to do, where it came from and since when, and I'm on it / Snooze / Open. Back does not
 * dismiss it; it closes only when nothing rings here any more.
 */
class WakeUpActivity : ComponentActivity() {
    private var view: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { /* only an answer closes it */ } })
        lifecycleScope.launch {
            WakeHub.ringing.collect { list ->
                val top = list.firstOrNull()
                if (top == null) { finish(); return@collect }
                val v = view
                if (v == null) {
                    val built = WakeScreen.build(this@WakeUpActivity, top,
                        onAck = { WakeHub.ack(this@WakeUpActivity, it) },
                        onSnooze = { WakeHub.snooze(this@WakeUpActivity, it) },
                        onOpen = { id, link -> WakeScreen.open(this@WakeUpActivity, link); WakeHub.ack(this@WakeUpActivity, id) })
                    view = built
                    setContentView(built)
                } else WakeScreen.bind(v, top)
                window.statusBarColor = WakeRules.colour(top.wake.severity).toInt()
                window.navigationBarColor = WakeRules.colour(top.wake.severity).toInt()
            }
        }
        // Opened from an alarm or the notification with nothing decided yet: decide now.
        if (WakeHub.ringing.value.isEmpty()) WakeHub.evaluate(this)
    }
}

/** The ringing screen's view, shared by the activity and the overlay. Built in code: no layout inflation, no theme. */
object WakeScreen {
    private const val TAG_KEY = 0x7a11

    private class Holder(val root: LinearLayout, val sev: TextView, val title: TextView, val what: TextView, val meta: TextView, val ack: Button, val snooze: Button, val open: Button, val more: TextView) {
        var id: String = ""
        var link: String = ""
    }

    fun build(context: Context, top: Ringing, onAck: (String) -> Unit, onSnooze: (String) -> Unit, onOpen: (String, String) -> Unit): View {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(48), dp(28), dp(36)) }
        fun text(size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(context).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); if (bold) typeface = Typeface.DEFAULT_BOLD; this.alpha = alpha
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        val sev = text(14f, bold = true, alpha = 0.9f).apply { letterSpacing = 0.14f }
        val title = text(34f, bold = true)
        val what = text(22f)
        val meta = text(14f, alpha = 0.8f)
        fun button(label: String, filled: Boolean) = Button(context).apply {
            text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(64)
            background = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); if (filled) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(2), Color.WHITE) } }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val ack = button("I'm on it", filled = true)
        val snooze = button("Snooze", filled = false)
        val open = button("Open", filled = false)
        val more = text(14f, alpha = 0.85f).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20) }
        listOf(sev, title, what, meta).forEach(root::addView)
        root.addView(View(context), LinearLayout.LayoutParams(1, dp(24)))
        listOf(ack, snooze, open, more).forEach(root::addView)
        val h = Holder(root, sev, title, what, meta, ack, snooze, open, more)
        ack.setOnClickListener { if (h.id.isNotEmpty()) onAck(h.id) }
        snooze.setOnClickListener { if (h.id.isNotEmpty()) onSnooze(h.id) }
        open.setOnClickListener { if (h.id.isNotEmpty()) onOpen(h.id, h.link) }
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(root) }
        scroll.setTag(TAG_KEY, h)
        bind(scroll, top)
        return scroll
    }

    fun bind(view: View, top: Ringing) {
        val h = view.getTag(TAG_KEY) as? Holder ?: return
        val w = top.wake
        val colour = WakeRules.colour(w.severity).toInt()
        view.setBackgroundColor(colour)
        h.id = w.id
        h.link = w.link
        h.sev.text = (WakeRules.label(w.severity) + if (w.cancelled) " · cancelled" else "").uppercase()
        h.title.text = WakeRules.shownTitle(w)
        h.what.text = w.whatToDo
        val since = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(w.ringAt))
        h.meta.text = "Since $since" + (if (w.firedBy.isNotBlank()) " · from ${w.firedBy}" else "") + (if (w.sourceKind.isNotBlank()) " · ${w.sourceKind}" else "")
        h.ack.setTextColor(colour)
        h.snooze.text = "Snooze ${WakeHub.snapshot.value.snoozeMinutes} min"
        h.open.visibility = if (w.link.isNotBlank()) View.VISIBLE else View.GONE
        val others = WakeHub.ringing.value.drop(1)
        h.more.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        h.more.text = if (others.isEmpty()) "" else "${others.size} more ringing:\n" + others.take(4).joinToString("\n") { "• ${WakeRules.label(it.wake.severity)}: ${WakeRules.shownTitle(it.wake)}" }
    }

    fun open(context: Context, link: String) {
        if (!link.startsWith("http://") && !link.startsWith("https://")) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
