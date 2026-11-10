package com.past9.phoneaos.screen.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is on the hidden display, as a list of things one can read or press. Read through Android's UI automation
 * link, which the shell user may open: the same one "uiautomator dump" uses. So the app itself needs no accessibility
 * service, and the person's own accessibility services keep running (FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).
 */
internal object Tree {
    private var ua: UiAutomation? = null
    private var thread: HandlerThread? = null
    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private const val DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 1

    @Synchronized private fun link(): UiAutomation {
        ua?.let { return it }
        val ht = HandlerThread("screen-tree").apply { start() }
        val connClass = Class.forName("android.app.IUiAutomationConnection")
        val conn = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val made = runCatching { UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, connClass).apply { isAccessible = true }.newInstance(ht.looper, conn) }
            .getOrElse { UiAutomation::class.java.getDeclaredConstructor(Context::class.java, connClass).apply { isAccessible = true }.newInstance(Sys.systemContext, conn) }
        try {
            try { UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType).invoke(made, DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
            catch (e: NoSuchMethodException) { UiAutomation::class.java.getMethod("connect").invoke(made) }
        } catch (t: Throwable) { ht.quitSafely(); throw Sys.cause(t) }
        val info = made.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        made.serviceInfo = info
        thread = ht; ua = made
        return made
    }

    @Synchronized fun unlink() {
        nodes = emptyList()
        ua?.let { u -> runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(u) } }
        ua = null
        thread?.quitSafely(); thread = null
    }

    private fun windows(displayId: Int): List<AccessibilityWindowInfo> {
        val u = link()
        var list = u.windowsOnAllDisplays.get(displayId)
        if (list == null) {
            // The link was made before the display existed, or the window list is a moment behind.
            Thread.sleep(150)
            list = u.windowsOnAllDisplays.get(displayId)
        }
        return (list ?: emptyList()).filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
    }

    /** Everything worth reading or pressing on [displayId], at most [max] entries. */
    @Synchronized fun dump(displayId: Int, max: Int): JSONObject {
        val found = ArrayList<AccessibilityNodeInfo>()
        val out = JSONArray(); val wins = JSONArray()
        val rect = Rect()
        var cut = false
        windows(displayId).forEachIndexed { wi, w ->
            val root = runCatching { w.root }.getOrNull()
            wins.put(JSONObject().put("i", wi).put("type", w.type).put("title", w.title?.toString().orEmpty())
                .put("pkg", root?.packageName?.toString().orEmpty()).put("focused", w.isFocused).put("active", w.isActive))
            if (root == null) return@forEachIndexed
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (found.size >= max) { cut = true; return }
                if (n.isVisibleToUser) {
                    val text = n.text?.toString().orEmpty().trim(); val desc = n.contentDescription?.toString().orEmpty().trim()
                    val hint = if (text.isEmpty()) n.hintText?.toString().orEmpty().trim() else ""
                    val interesting = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable || text.isNotEmpty() || desc.isNotEmpty()
                    n.getBoundsInScreen(rect)
                    if (interesting && !rect.isEmpty) {
                        val flags = buildString {
                            if (n.isClickable) append('c'); if (n.isLongClickable) append('l'); if (n.isEditable) append('e'); if (n.isScrollable) append('s')
                            if (n.isFocused) append('f'); if (n.isCheckable) append('k'); if (n.isChecked) append('K'); if (n.isSelected) append('x')
                            if (n.isPassword) append('p'); if (!n.isEnabled) append('d')
                        }
                        val o = JSONObject().put("i", found.size).put("w", wi).put("c", n.className?.toString().orEmpty().substringAfterLast('.'))
                            .put("b", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom)).put("f", flags)
                        if (text.isNotEmpty()) o.put("t", if (n.isPassword) "(hidden)" else text.take(200))
                        if (desc.isNotEmpty()) o.put("d", desc.take(120))
                        if (hint.isNotEmpty()) o.put("h", hint.take(80))
                        n.viewIdResourceName?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { o.put("r", it) }
                        out.put(o); found.add(n)
                    }
                }
                if (depth > 60) return
                for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
        }
        nodes = found
        return JSONObject().put("ok", true).put("nodes", out).put("windows", wins).put("cut", cut)
    }

    private fun focusedField(displayId: Int): AccessibilityNodeInfo? {
        for (w in windows(displayId)) runCatching { w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { return it }
        return null
    }

    private fun target(displayId: Int, index: Int): AccessibilityNodeInfo? =
        if (index >= 0) nodes.getOrNull(index) else focusedField(displayId)

    /** Put [text] in a field: the one at [index] from the last dump, or the one that has the focus. */
    @Synchronized fun setText(displayId: Int, index: Int, text: String, append: Boolean): JSONObject {
        val n = target(displayId, index) ?: return fail(if (index >= 0) "No such element. Look at the screen again." else "No text field has the focus. Tap the field first.")
        runCatching { n.refresh() }
        if (!n.isEditable) return fail("That is not a text field.")
        val whole = if (append) n.text?.toString().orEmpty() + text else text
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, whole) })
        Thread.sleep(120); runCatching { n.refresh() }
        return JSONObject().put("ok", ok).put("text", if (n.isPassword) "(hidden)" else n.text?.toString().orEmpty()).apply { if (!ok) put("error", "The app refused the text.") }
    }

    /** click, long, focus, forward, backward, enter (the keyboard's own Send, Search or Go). */
    @Synchronized fun act(displayId: Int, index: Int, action: String): JSONObject {
        val n = target(displayId, index) ?: return fail("No such element. Look at the screen again.")
        val ok = when (action) {
            "click" -> n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long" -> n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "focus" -> n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) or n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "forward" -> n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "backward" -> n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "enter" -> if (Sys.sdk >= 30) n.performAction(android.R.id.accessibilityActionImeEnter) else false
            else -> return fail("Unknown action $action")
        }
        return JSONObject().put("ok", ok)
    }

    /** Wait until the screen has stopped changing for [quietMs], at most [timeoutMs]. True when it settled. */
    fun waitIdle(quietMs: Long, timeoutMs: Long): Boolean = try { link().waitForIdle(quietMs, timeoutMs); true } catch (t: Throwable) { false }

    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why)
}
