package com.focuswall.app.data

import android.content.Context
import com.focuswall.app.core.Json
import java.util.concurrent.Executors

/** Синхронизация с Firestore: побеждает более свежая версия (по updatedAt), отдельно для настроек и для сессии. */
object SyncManager {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var lastRun = 0L

    /** Запускает синхронизацию в фоне не чаще раза в minIntervalMs. */
    fun syncAsync(context: Context, minIntervalMs: Long = 45_000, done: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (now - lastRun < minIntervalMs) return
        lastRun = now
        val app = context.applicationContext
        executor.execute {
            sync(app)
            done?.invoke()
        }
    }

    fun sync(context: Context) {
        if (!FirebaseRest.configured) return
        val store = Store.get(context)
        val saved = store.auth ?: return
        try {
            val auth = FirebaseRest.fresh(saved)
            if (auth != saved) store.auth = auth
            val remote = FirebaseRest.fetch(auth)

            val local = store.config
            val remoteConfig = Json.config(remote?.config)
            if (remote != null && remoteConfig != null && remote.configUpdatedAt > local.updatedAt) {
                store.config = remoteConfig.copy(updatedAt = remote.configUpdatedAt)
            } else if (remote == null || local.updatedAt > remote.configUpdatedAt) {
                val ts = if (local.updatedAt > 0) local.updatedAt else System.currentTimeMillis()
                FirebaseRest.push(auth, "config", Json.toJson(local.copy(updatedAt = ts)), ts)
            }

            if (remote != null && remote.sessionUpdatedAt > store.sessionUpdatedAt) {
                store.setSession(Json.session(remote.session), remote.sessionUpdatedAt)
            } else if (store.sessionUpdatedAt > (remote?.sessionUpdatedAt ?: 0)) {
                FirebaseRest.push(auth, "session", Json.toJson(store.session), store.sessionUpdatedAt)
            }
            store.lastSync = System.currentTimeMillis()
            store.syncError = ""
        } catch (e: Exception) {
            store.syncError = e.message ?: e.toString()
        }
    }
}
