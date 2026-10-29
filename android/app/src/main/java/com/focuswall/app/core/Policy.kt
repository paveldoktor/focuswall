package com.focuswall.app.core

import java.time.LocalDateTime
import java.time.ZoneId

// Та же логика, что в extension/core.js. При изменениях правьте оба файла.

enum class PolicyMode { OFF, BLOCK, ALLOW }

/** BLOCK: запрещено то, что в items. ALLOW: запрещено всё, кроме items. */
data class Policy(val mode: PolicyMode, val items: Set<String>)

object Rules {
    private fun minutesOf(hhmm: String): Int {
        val p = hhmm.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun jsDay(t: LocalDateTime) = t.dayOfWeek.value % 7

    fun scheduleActive(s: Schedule, now: LocalDateTime): Boolean {
        if (!s.enabled) return false
        val start = minutesOf(s.start)
        val end = minutesOf(s.end)
        val t = now.hour * 60 + now.minute
        val today = jsDay(now)
        val yesterday = (today + 6) % 7
        if (start == end) return today in s.days
        if (start < end) return today in s.days && t >= start && t < end
        return (today in s.days && t >= start) || (yesterday in s.days && t < end)
    }

    fun sessionActive(session: Session?, nowMs: Long) = session != null && session.endsAt > nowMs

    fun activeListIds(c: Config, session: Session?, now: LocalDateTime, nowMs: Long): Set<String> {
        val ids = mutableSetOf<String>()
        if (sessionActive(session, nowMs)) ids += session!!.listIds
        c.schedules.filter { scheduleActive(it, now) }.forEach { ids += it.listIds }
        return ids
    }

    private fun policy(c: Config, session: Session?, now: LocalDateTime, nowMs: Long, pick: (BlockList) -> List<String>): Policy {
        val ids = activeListIds(c, session, now, nowMs)
        val lists = c.blocklists.filter { it.id in ids }
        val allowLists = lists.filter { it.mode == ListMode.ALLOW }
        if (allowLists.isNotEmpty()) {
            val allowed = allowLists.flatMap(pick).toMutableSet()
            lists.filter { it.mode == ListMode.BLOCK }.forEach { allowed -= pick(it).toSet() }
            return Policy(PolicyMode.ALLOW, allowed)
        }
        val blocked = lists.flatMap(pick).toSet()
        return if (blocked.isEmpty()) Policy(PolicyMode.OFF, emptySet()) else Policy(PolicyMode.BLOCK, blocked)
    }

    fun sitePolicy(c: Config, s: Session?, now: LocalDateTime, nowMs: Long) = policy(c, s, now, nowMs) { it.domains }
    fun appPolicy(c: Config, s: Session?, now: LocalDateTime, nowMs: Long) = policy(c, s, now, nowMs) { it.androidApps }

    fun hostMatches(host: String, domains: Collection<String>): String? {
        val h = host.lowercase().removePrefix("www.")
        return domains.firstOrNull { h == it || h.endsWith(".$it") }
    }

    fun isHostBlocked(host: String, p: Policy): Boolean = when (p.mode) {
        PolicyMode.OFF -> false
        PolicyMode.BLOCK -> hostMatches(host, p.items) != null
        PolicyMode.ALLOW -> hostMatches(host, p.items + Defaults.ALWAYS_ALLOWED_DOMAINS) == null
    }

    fun isAppBlocked(pkg: String, p: Policy): Boolean = when (p.mode) {
        PolicyMode.OFF -> false
        PolicyMode.BLOCK -> pkg in p.items
        PolicyMode.ALLOW -> pkg !in p.items
    }

    fun hardActive(c: Config, s: Session?, now: LocalDateTime, nowMs: Long): Boolean =
        (sessionActive(s, nowMs) && s!!.hard) || c.schedules.any { it.hard && scheduleActive(it, now) }

    /** Текст ошибки, если новая конфигурация ослабляет действующую жёсткую блокировку. */
    fun hardModeViolation(old: Config, new: Config, s: Session?, now: LocalDateTime, nowMs: Long): String? {
        if (!hardActive(old, s, now, nowMs)) return null
        for (id in activeListIds(old, s, now, nowMs)) {
            val before = old.blocklists.find { it.id == id } ?: continue
            val after = new.blocklists.find { it.id == id }
                ?: return "Список «${before.name}» сейчас действует, удалить его нельзя."
            if (after.mode != before.mode) return "Список «${before.name}» сейчас действует, менять его тип нельзя."
            for (pick in listOf<(BlockList) -> List<String>>({ it.domains }, { it.androidApps }, { it.desktopApps })) {
                val a = pick(before)
                val b = pick(after)
                val loosened = if (before.mode == ListMode.ALLOW) b.filter { it !in a } else a.filter { it !in b }
                if (loosened.isNotEmpty()) return "Идёт жёсткая блокировка: «${before.name}» можно только ужесточить."
            }
        }
        for (sc in old.schedules) {
            if (!sc.hard || !scheduleActive(sc, now)) continue
            val after = new.schedules.find { it.id == sc.id }
            val same = after != null && after.enabled && after.hard && after.start == sc.start && after.end == sc.end &&
                after.days.containsAll(sc.days) && after.listIds.containsAll(sc.listIds)
            if (!same) return "Расписание «${sc.name}» сейчас действует, изменить его можно после ${sc.end}."
        }
        return null
    }

    /**
     * Ключ текущей ночи: id жёсткого расписания и дата, когда оно началось (после полуночи это вчера).
     * Нужен, чтобы выдавать одну разблокировку на ночь. null, если жёсткого расписания сейчас нет.
     */
    fun nightKey(c: Config, now: LocalDateTime): String? {
        val sc = c.schedules.firstOrNull { it.hard && scheduleActive(it, now) } ?: return null
        val t = now.hour * 60 + now.minute
        val crossesMidnight = minutesOf(sc.start) > minutesOf(sc.end)
        val startDate = if (crossesMidnight && t < minutesOf(sc.start)) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return "${sc.id}:$startDate"
    }

    /** Когда закончится текущая блокировка, в миллисекундах; null, если ничего не активно. */
    fun blockedUntil(c: Config, s: Session?, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        var until = if (sessionActive(s, nowMs)) s!!.endsAt else 0L
        for (sc in c.schedules.filter { scheduleActive(it, now) }) {
            val m = minutesOf(sc.end)
            var end = now.toLocalDate().atTime(m / 60, m % 60)
            if (!end.isAfter(now)) end = end.plusDays(1)
            until = maxOf(until, end.atZone(zone).toInstant().toEpochMilli())
        }
        return until.takeIf { it > 0 }
    }

    fun normalizeDomain(input: String): String {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.replace(Regex("^[a-z]+://"), "").removePrefix("*.").removePrefix("www.")
        s = s.split(Regex("[/?#:\\s]")).first()
        return if (Regex("^[a-z0-9.-]+\\.[a-z0-9-]{2,}$").matches(s)) s else ""
    }

    fun formatLeft(ms: Long): String {
        val total = maxOf(0L, (ms + 59_999) / 60_000)
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}
