package com.focuswall.app.data

import android.content.Context
import android.content.SharedPreferences
import com.focuswall.app.core.Config
import com.focuswall.app.core.Defaults
import com.focuswall.app.core.Json
import com.focuswall.app.core.Policy
import com.focuswall.app.core.PolicyMode
import com.focuswall.app.core.Rules
import com.focuswall.app.core.Session
import java.time.LocalDateTime

data class Auth(val uid: String, val email: String, val idToken: String, val refreshToken: String, val expiresAt: Long)

/** Локальное состояние приложения. Все службы читают его отсюда. */
class Store private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("focuswall", Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<() -> Unit>()

    var config: Config
        get() = Json.config(prefs.getString("config", null)) ?: Defaults.config()
        set(v) = edit { putString("config", Json.toJson(v)) }

    val session: Session? get() = Json.session(prefs.getString("session", null))
    val sessionUpdatedAt: Long get() = prefs.getLong("sessionUpdatedAt", 0)

    fun setSession(s: Session?, updatedAt: Long) = edit {
        putString("session", Json.toJson(s))
        putLong("sessionUpdatedAt", updatedAt)
    }

    var auth: Auth?
        get() {
            val uid = prefs.getString("auth.uid", null) ?: return null
            return Auth(
                uid,
                prefs.getString("auth.email", "") ?: "",
                prefs.getString("auth.idToken", "") ?: "",
                prefs.getString("auth.refreshToken", "") ?: "",
                prefs.getLong("auth.expiresAt", 0),
            )
        }
        set(a) = edit {
            if (a == null) {
                listOf("auth.uid", "auth.email", "auth.idToken", "auth.refreshToken", "auth.expiresAt").forEach { remove(it) }
            } else {
                putString("auth.uid", a.uid)
                putString("auth.email", a.email)
                putString("auth.idToken", a.idToken)
                putString("auth.refreshToken", a.refreshToken)
                putLong("auth.expiresAt", a.expiresAt)
            }
        }

    /** Пользователь включил фильтр сайтов (локальный VPN). */
    var vpnEnabled: Boolean
        get() = prefs.getBoolean("vpnEnabled", false)
        set(v) = edit { putBoolean("vpnEnabled", v) }

    var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        set(v) = edit { putLong("lastSync", v) }
    var syncError: String
        get() = prefs.getString("syncError", "") ?: ""
        set(v) = edit { putString("syncError", v) }

    // ----- Разовая разблокировка на ночь -----

    /** Когда закончится разблокировка, мс. */
    var passUntil: Long
        get() = prefs.getLong("passUntil", 0)
        private set(v) = edit { putLong("passUntil", v) }

    /** Ключ ночи, в которую разблокировка уже использована (см. Rules.nightKey). */
    private var passNight: String
        get() = prefs.getString("passNight", "") ?: ""
        set(v) = edit { putString("passNight", v) }

    fun passActive(): Boolean = passUntil > System.currentTimeMillis()

    /** Можно ли сейчас взять разблокировку: идёт жёсткая ночь, и в эту ночь её ещё не брали. */
    fun passAvailable(): Boolean {
        val key = Rules.nightKey(config, LocalDateTime.now()) ?: return false
        return !passActive() && key != passNight
    }

    /** Включает разблокировку на [minutes] минут. Один раз за ночь; false, если нельзя. */
    fun startPass(minutes: Int = PASS_MINUTES): Boolean {
        if (!passAvailable()) return false
        val key = Rules.nightKey(config, LocalDateTime.now()) ?: return false
        // Сначала помечаем ночь использованной, потом включаем: если приложение убьют между шагами, второй попытки не будет.
        passNight = key
        passUntil = System.currentTimeMillis() + minutes * 60_000L
        return true
    }

    // ----- Вычисления «что блокировать сейчас» -----

    private val noPolicy = Policy(PolicyMode.OFF, emptySet())

    fun appPolicy(): Policy =
        if (passActive()) noPolicy else Rules.appPolicy(config, session, LocalDateTime.now(), System.currentTimeMillis())
    fun sitePolicy(): Policy =
        if (passActive()) noPolicy else Rules.sitePolicy(config, session, LocalDateTime.now(), System.currentTimeMillis())
    fun hardActive(): Boolean = Rules.hardActive(config, session, LocalDateTime.now(), System.currentTimeMillis())
    fun blockedUntil(): Long? = Rules.blockedUntil(config, session, LocalDateTime.now())

    /** Сохраняет конфигурацию от пользователя. Бросает исключение, если она ослабляет жёсткую блокировку. */
    fun saveUserConfig(new: Config) {
        val err = Rules.hardModeViolation(config, new, session, LocalDateTime.now(), System.currentTimeMillis())
        if (err != null) throw IllegalStateException(err)
        config = new.copy(updatedAt = System.currentTimeMillis())
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    companion object {
        const val PASS_MINUTES = 3
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }
    }
}
