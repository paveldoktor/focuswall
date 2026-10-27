package com.focuswall.app.core

import org.json.JSONArray
import org.json.JSONObject

// Формат совпадает с расширением для браузера (extension/core.js), оба хранят его в Firestore как JSON-строку.

enum class ListMode(val json: String) {
    BLOCK("block"), ALLOW("allow");

    companion object {
        fun of(s: String?) = if (s == "allow") ALLOW else BLOCK
    }
}

data class BlockList(
    val id: String,
    val name: String,
    val mode: ListMode = ListMode.BLOCK,
    val domains: List<String> = emptyList(),
    val androidApps: List<String> = emptyList(),
    val desktopApps: List<String> = emptyList(),
)

data class Schedule(
    val id: String,
    val name: String,
    val listIds: List<String>,
    /** 0 = воскресенье … 6 = суббота, как в JavaScript. */
    val days: List<Int>,
    val start: String,
    val end: String,
    val enabled: Boolean = true,
    val hard: Boolean = false,
)

data class Config(
    val blocklists: List<BlockList>,
    val schedules: List<Schedule>,
    val updatedAt: Long = 0,
)

data class Session(
    val id: String,
    val listIds: List<String>,
    val startedAt: Long,
    val endsAt: Long,
    val hard: Boolean,
    val device: String,
)

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { getInt(it) }

object Json {
    fun config(s: String?): Config? {
        if (s.isNullOrBlank() || s == "null") return null
        val o = JSONObject(s)
        val lists = o.optJSONArray("blocklists") ?: JSONArray()
        val schedules = o.optJSONArray("schedules") ?: JSONArray()
        return Config(
            blocklists = (0 until lists.length()).map { i ->
                val l = lists.getJSONObject(i)
                BlockList(
                    id = l.getString("id"),
                    name = l.optString("name"),
                    mode = ListMode.of(l.optString("mode")),
                    domains = l.optJSONArray("domains").strings(),
                    androidApps = l.optJSONArray("androidApps").strings(),
                    desktopApps = l.optJSONArray("desktopApps").strings(),
                )
            },
            schedules = (0 until schedules.length()).map { i ->
                val x = schedules.getJSONObject(i)
                Schedule(
                    id = x.getString("id"),
                    name = x.optString("name"),
                    listIds = x.optJSONArray("listIds").strings(),
                    days = x.optJSONArray("days").ints(),
                    start = x.optString("start", "00:00"),
                    end = x.optString("end", "00:00"),
                    enabled = x.optBoolean("enabled", true),
                    hard = x.optBoolean("hard", false),
                )
            },
            updatedAt = o.optLong("updatedAt", 0),
        )
    }

    fun toJson(c: Config): String = JSONObject().apply {
        put("blocklists", JSONArray(c.blocklists.map { l ->
            JSONObject()
                .put("id", l.id).put("name", l.name).put("mode", l.mode.json)
                .put("domains", JSONArray(l.domains))
                .put("androidApps", JSONArray(l.androidApps))
                .put("desktopApps", JSONArray(l.desktopApps))
        }))
        put("schedules", JSONArray(c.schedules.map { s ->
            JSONObject()
                .put("id", s.id).put("name", s.name)
                .put("listIds", JSONArray(s.listIds)).put("days", JSONArray(s.days))
                .put("start", s.start).put("end", s.end)
                .put("enabled", s.enabled).put("hard", s.hard)
        }))
        put("updatedAt", c.updatedAt)
    }.toString()

    fun session(s: String?): Session? {
        if (s.isNullOrBlank() || s == "null") return null
        val o = JSONObject(s)
        return Session(
            id = o.optString("id"),
            listIds = o.optJSONArray("listIds").strings(),
            startedAt = o.optLong("startedAt"),
            endsAt = o.optLong("endsAt"),
            hard = o.optBoolean("hard"),
            device = o.optString("device"),
        )
    }

    fun toJson(s: Session?): String = if (s == null) "null" else JSONObject()
        .put("id", s.id).put("listIds", JSONArray(s.listIds))
        .put("startedAt", s.startedAt).put("endsAt", s.endsAt)
        .put("hard", s.hard).put("device", s.device)
        .toString()
}

object Defaults {
    /** Сеть приложению всегда нужна для входа и синхронизации. */
    val ALWAYS_ALLOWED_DOMAINS = listOf("googleapis.com", "accounts.google.com", "gstatic.com")

    fun config() = Config(
        blocklists = listOf(
            BlockList(
                id = "night",
                name = "Ночь: только звонки и Сбербанк",
                mode = ListMode.ALLOW,
                // Часы оставлены, иначе экран сработавшего будильника закрывался бы блокировкой.
                androidApps = listOf(
                    "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
                    "ru.sberbankmobile",
                    "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
                ),
            ),
            BlockList(
                id = "social",
                name = "Соцсети",
                domains = listOf("vk.com", "instagram.com", "facebook.com", "tiktok.com", "x.com", "twitter.com", "reddit.com", "ok.ru"),
                androidApps = listOf(
                    "com.vkontakte.android", "com.instagram.android", "com.facebook.katana", "com.zhiliaoapp.musically",
                    "com.twitter.android", "com.reddit.frontpage", "ru.ok.android",
                ),
            ),
            BlockList(
                id = "video",
                name = "Видео",
                domains = listOf("youtube.com", "twitch.tv", "rutube.ru"),
                androidApps = listOf("com.google.android.youtube", "tv.twitch.android.app", "ru.rutube.app"),
            ),
        ),
        schedules = listOf(
            Schedule("night", "Ночь", listOf("night"), (0..6).toList(), "21:00", "05:00", enabled = true, hard = true),
        ),
    )
}
