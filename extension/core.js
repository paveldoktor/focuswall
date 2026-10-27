// Общая логика блокировки. Та же логика повторена в Android-приложении (Blocking.kt).

export const DAY_NAMES = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];

export function uid() {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
}

// Список бывает двух видов:
//   mode 'block' — блокировать перечисленное;
//   mode 'allow' — блокировать всё, КРОМЕ перечисленного (белый список).
export function defaultConfig() {
  return {
    blocklists: [
      {
        id: 'night',
        name: 'Ночь: только самое нужное',
        mode: 'allow',
        domains: [],
        androidApps: [
          'com.google.android.dialer', 'com.android.dialer', 'com.samsung.android.dialer',
          'com.google.android.deskclock', 'com.android.deskclock', 'com.sec.android.app.clockpackage',
        ],
        desktopApps: [],
      },
      {
        id: 'social',
        name: 'Соцсети',
        mode: 'block',
        domains: ['vk.com', 'instagram.com', 'facebook.com', 'tiktok.com', 'x.com', 'twitter.com', 'reddit.com', 'ok.ru'],
        androidApps: ['com.vkontakte.android', 'com.instagram.android', 'com.facebook.katana', 'com.zhiliaoapp.musically', 'com.twitter.android', 'com.reddit.frontpage', 'ru.ok.android'],
        desktopApps: [],
      },
      {
        id: 'video',
        name: 'Видео',
        mode: 'block',
        domains: ['youtube.com', 'twitch.tv', 'rutube.ru'],
        androidApps: ['com.google.android.youtube', 'tv.twitch.android.app', 'ru.rutube.app'],
        desktopApps: [],
      },
    ],
    schedules: [
      { id: 'night', name: 'Ночь', listIds: ['night'], days: [0, 1, 2, 3, 4, 5, 6], start: '21:00', end: '05:00', enabled: true, hard: true },
    ],
    updatedAt: 0,
  };
}

// "https://www.YouTube.com/watch?v=1" -> "youtube.com"
export function normalizeDomain(input) {
  let s = String(input || '').trim().toLowerCase();
  if (!s) return '';
  s = s.replace(/^[a-z]+:\/\//, '').replace(/^\*\./, '').replace(/^www\./, '');
  s = s.split(/[\/?#:\s]/)[0];
  return /^[a-z0-9.-]+\.[a-z0-9-]{2,}$/.test(s) ? s : '';
}

export function parseList(text, normalize) {
  const out = [];
  for (const line of String(text || '').split(/[\n,]+/)) {
    const v = normalize ? normalize(line) : line.trim();
    if (v && !out.includes(v)) out.push(v);
  }
  return out;
}

function minutesOf(hhmm) {
  const [h, m] = String(hhmm || '0:0').split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
}

export function scheduleActive(s, now = new Date()) {
  if (!s.enabled) return false;
  const start = minutesOf(s.start);
  const end = minutesOf(s.end);
  const t = now.getHours() * 60 + now.getMinutes();
  const today = now.getDay();
  const yesterday = (today + 6) % 7;
  if (start === end) return s.days.includes(today);
  if (start < end) return s.days.includes(today) && t >= start && t < end;
  // Через полночь, например 22:00–07:00: утренняя часть относится ко вчерашнему дню.
  return (s.days.includes(today) && t >= start) || (s.days.includes(yesterday) && t < end);
}

export function sessionActive(session, now = Date.now()) {
  return !!(session && session.endsAt > now);
}

export function activeListIds(config, session, now = new Date()) {
  const ids = new Set();
  if (sessionActive(session, now.getTime())) session.listIds.forEach((id) => ids.add(id));
  for (const s of config.schedules || []) {
    if (scheduleActive(s, now)) s.listIds.forEach((id) => ids.add(id));
  }
  return ids;
}

// Итоговая политика для сайтов.
//   { mode: 'off' } — ничего не блокируем
//   { mode: 'block', domains } — блокируем перечисленные домены
//   { mode: 'allow', domains } — блокируем всё, кроме перечисленных доменов
// Если одновременно активны белый и чёрный списки, белый строже и побеждает.
export function sitePolicy(config, session, now = new Date()) {
  const ids = activeListIds(config, session, now);
  const lists = (config.blocklists || []).filter((l) => ids.has(l.id));
  const allowLists = lists.filter((l) => l.mode === 'allow');
  if (allowLists.length) {
    const allowed = new Set(allowLists.flatMap((l) => l.domains));
    // Сайты из активного чёрного списка запрещены даже если попали в белый.
    lists.filter((l) => l.mode !== 'allow').forEach((l) => l.domains.forEach((d) => allowed.delete(d)));
    return { mode: 'allow', domains: [...allowed] };
  }
  const blocked = new Set(lists.flatMap((l) => l.domains));
  return blocked.size ? { mode: 'block', domains: [...blocked] } : { mode: 'off', domains: [] };
}

export function isHostBlocked(host, policy) {
  if (policy.mode === 'off') return false;
  const hit = hostMatches(host, policy.domains);
  return policy.mode === 'block' ? !!hit : !hit;
}

export function hostMatches(host, domains) {
  host = String(host || '').toLowerCase().replace(/^www\./, '');
  return domains.find((d) => host === d || host.endsWith('.' + d)) || null;
}

export function hardActive(config, session, now = new Date()) {
  if (sessionActive(session, now.getTime()) && session.hard) return true;
  return (config.schedules || []).some((s) => s.hard && scheduleActive(s, now));
}

// Пока идёт жёсткая сессия или жёсткое расписание, ослабить блокировку нельзя:
// из чёрных списков нельзя убирать пункты, в белые нельзя добавлять, активное расписание нельзя менять.
export function hardModeViolation(oldConfig, newConfig, session, now = new Date()) {
  if (!hardActive(oldConfig, session, now)) return null;
  const ids = activeListIds(oldConfig, session, now);
  for (const id of ids) {
    const before = oldConfig.blocklists.find((l) => l.id === id);
    const after = newConfig.blocklists.find((l) => l.id === id);
    if (!before) continue;
    if (!after) return `Список «${before.name}» сейчас действует, удалить его нельзя.`;
    if ((after.mode || 'block') !== (before.mode || 'block')) return `Список «${before.name}» сейчас действует, менять его тип нельзя.`;
    for (const key of ['domains', 'androidApps', 'desktopApps']) {
      const a = before[key] || [];
      const b = after[key] || [];
      const loosened = before.mode === 'allow' ? b.filter((x) => !a.includes(x)) : a.filter((x) => !b.includes(x));
      if (loosened.length) return `Идёт жёсткая блокировка: «${before.name}» можно только ужесточить, до её окончания.`;
    }
  }
  for (const s of oldConfig.schedules || []) {
    if (!s.hard || !scheduleActive(s, now)) continue;
    const after = (newConfig.schedules || []).find((x) => x.id === s.id);
    const same = after && after.enabled && after.hard && after.start === s.start && after.end === s.end
      && s.days.every((d) => after.days.includes(d)) && s.listIds.every((id) => after.listIds.includes(id));
    if (!same) return `Расписание «${s.name}» сейчас действует, изменить его можно после ${s.end}.`;
  }
  return null;
}

export function formatLeft(ms) {
  const total = Math.max(0, Math.ceil(ms / 60000));
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h ? `${h} ч ${m} мин` : `${m} мин`;
}

// Когда закончится текущая блокировка (для экрана блокировки). null — если ничего не активно.
export function blockedUntil(config, session, now = new Date()) {
  let until = 0;
  if (sessionActive(session, now.getTime())) until = session.endsAt;
  for (const s of config.schedules || []) {
    if (!scheduleActive(s, now)) continue;
    const [h, m] = String(s.end).split(':').map(Number);
    const end = new Date(now);
    end.setHours(h || 0, m || 0, 0, 0);
    if (end <= now) end.setDate(end.getDate() + 1);
    until = Math.max(until, end.getTime());
  }
  return until || null;
}
