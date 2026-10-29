package com.focuswall.app.block

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.focuswall.app.core.PolicyMode
import com.focuswall.app.core.Rules
import com.focuswall.app.data.Store
import com.focuswall.app.data.SyncManager

/**
 * Следит за тем, какое приложение на экране, и закрывает запрещённые.
 * В браузерах дополнительно читает адресную строку и закрывает запрещённые сайты.
 */
class BlockerAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var lastBrowserCheck = 0L
    private var lastBlockAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            SyncManager.syncAsync(this@BlockerAccessibilityService)
            DnsVpnService.ensureRunning(this@BlockerAccessibilityService)
            // Если блокировка включилась по расписанию, пока открыто запрещённое приложение.
            rootInActiveWindow?.packageName?.toString()?.let { checkApp(it) }
            handler.postDelayed(this, 30_000)
        }
    }

    private var passOverlay: TextView? = null
    private val storeListener: () -> Unit = { handler.post { if (store.passActive()) startPassTimer() } }

    private val passTick = object : Runnable {
        override fun run() {
            val left = store.passUntil - System.currentTimeMillis()
            if (left > 0) {
                passOverlay?.text = "Разблокировано ещё %d:%02d, потом снова блокировка".format(left / 60_000, left / 1000 % 60)
                handler.postDelayed(this, 1000)
            } else {
                removePassOverlay()
                // Разблокировка кончилась: сразу закрываем то, что открыто сейчас.
                rootInActiveWindow?.packageName?.toString()?.let { pkg -> if (!checkApp(pkg)) checkBrowser(pkg, force = true) }
            }
        }
    }

    /** Показывает обратный отсчёт внизу экрана поверх любых приложений. */
    private fun startPassTimer() {
        if (passOverlay == null) {
            val view = TextView(this).apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6151918.toInt())
                gravity = Gravity.CENTER
                setPadding(24, 28, 24, 28)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM }
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, lp) }.onSuccess { passOverlay = view }
        }
        handler.removeCallbacks(passTick)
        handler.post(passTick)
    }

    private fun removePassOverlay() {
        passOverlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        passOverlay = null
    }

    override fun onServiceConnected() {
        store = Store.get(this)
        store.addListener(storeListener)
        handler.post(tick)
        if (store.passActive()) startPassTimer()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(passTick)
        store.removeListener(storeListener)
        removePassOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!checkApp(pkg)) checkBrowser(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> checkBrowser(pkg, force = false)
        }
    }

    /** true, если приложение заблокировано и закрыто. */
    private fun checkApp(pkg: String): Boolean {
        if (pkg in alwaysAllowed()) return false
        if (store.hardActive() && isTryingToDisableUs(pkg)) {
            block(pkg, "Во время жёсткой блокировки FocusWall нельзя отключить")
            return true
        }
        val policy = store.appPolicy()
        if (policy.mode == PolicyMode.OFF || !Rules.isAppBlocked(pkg, policy)) return false
        // Системные экраны (настройки, установщик) в белом списке тоже закрываются: иначе блокировку легко снять.
        block(pkg, appLabel(pkg))
        return true
    }

    private fun checkBrowser(pkg: String, force: Boolean) {
        val ids = BROWSER_URL_BARS[pkg] ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastBrowserCheck < 800) return
        lastBrowserCheck = now
        val policy = store.sitePolicy()
        if (policy.mode == PolicyMode.OFF) return
        val root = rootInActiveWindow ?: return
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull() ?: continue
            if (node.isFocused) return // пользователь ещё печатает адрес
            val host = Rules.normalizeDomain(node.text?.toString() ?: "")
            if (host.isNotEmpty() && Rules.isHostBlocked(host, policy)) block(pkg, host)
            return
        }
    }

    /** Экран настроек или удаления, где упоминается FocusWall. */
    private fun isTryingToDisableUs(pkg: String): Boolean {
        if (pkg !in SETTINGS_PACKAGES) return false
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText("FocusWall").isNotEmpty()
    }

    private fun block(pkg: String, what: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 600) return
        lastBlockAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockedActivity.EXTRA_WHAT, what)
                .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg),
        )
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private var cachedAllowed: Set<String>? = null
    private var cachedAllowedAt = 0L

    /** Без этих пакетов телефоном нельзя пользоваться совсем: рабочий стол, звонки, клавиатура, системный интерфейс. */
    private fun alwaysAllowed(): Set<String> {
        val now = System.currentTimeMillis()
        cachedAllowed?.let { if (now - cachedAllowedAt < 60_000) return it }
        val set = mutableSetOf(packageName, "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.incallui")
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        home?.activityInfo?.packageName?.let { set += it }
        runCatching { (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage }.getOrNull()?.let { set += it }
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.forEach { set += it.packageName }
        }
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { set += it }
        cachedAllowed = set
        cachedAllowedAt = now
        return set
    }

    companion object {
        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings", "com.samsung.android.settings", "com.miui.securitycenter",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.permissioncontroller",
        )

        /** id адресной строки в популярных браузерах. */
        private val BROWSER_URL_BARS = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.opera.browser" to listOf("url_field"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "com.yandex.browser" to listOf("bro_omnibar_address_title_text", "bro_omnibar_address_title"),
        )
    }
}
