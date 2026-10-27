import { defaultConfig, hardActive, hardModeViolation, isHostBlocked, sessionActive, sitePolicy, uid } from './core.js';
import { firebaseConfigured } from './config.js';
import { fetchRemote, freshAuth, pushPart, signInWithGoogle } from './firebase.js';

const BLOCKED_PAGE = '/blocked.html';

async function getState() {
  const s = await chrome.storage.local.get(['config', 'session', 'sessionUpdatedAt', 'auth', 'lastSync', 'syncError']);
  if (!s.config) {
    s.config = defaultConfig();
    await chrome.storage.local.set({ config: s.config });
  }
  return {
    config: s.config,
    session: s.session || null,
    sessionUpdatedAt: s.sessionUpdatedAt || 0,
    auth: s.auth || null,
    lastSync: s.lastSync || 0,
    syncError: s.syncError || '',
  };
}

// ---------- Блокировка ----------

// Без этих адресов не работают вход и синхронизация, поэтому они разрешены всегда.
const ALWAYS_ALLOWED = ['accounts.google.com', 'googleapis.com', 'gstatic.com'];

function rulesFor(policy) {
  if (policy.mode === 'block') {
    return policy.domains.flatMap((d, i) => [
      {
        id: i * 2 + 1,
        priority: 1,
        action: { type: 'redirect', redirect: { extensionPath: `${BLOCKED_PAGE}?u=${encodeURIComponent(d)}` } },
        condition: { requestDomains: [d], resourceTypes: ['main_frame'] },
      },
      {
        id: i * 2 + 2,
        priority: 1,
        action: { type: 'block' },
        condition: { requestDomains: [d], resourceTypes: ['sub_frame', 'xmlhttprequest', 'media', 'websocket'] },
      },
    ]);
  }
  if (policy.mode === 'allow') {
    const rules = [
      {
        id: 1,
        priority: 1,
        action: {
          type: 'redirect',
          redirect: { regexSubstitution: `chrome-extension://${chrome.runtime.id}${BLOCKED_PAGE}?u=\\1` },
        },
        condition: { regexFilter: '^https?://([^/:?#]+).*$', resourceTypes: ['main_frame'] },
      },
      { id: 2, priority: 1, action: { type: 'block' }, condition: { regexFilter: '^https?://', resourceTypes: ['sub_frame'] } },
    ];
    const allowed = [...policy.domains, ...ALWAYS_ALLOWED];
    rules.push({ id: 3, priority: 2, action: { type: 'allow' }, condition: { requestDomains: allowed, resourceTypes: ['main_frame', 'sub_frame'] } });
    return rules;
  }
  return [];
}

async function applyRules() {
  const { config, session } = await getState();
  const policy = sitePolicy(config, session);
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: existing.map((r) => r.id),
    addRules: rulesFor(policy),
  });

  // Уже открытые вкладки с заблокированными сайтами тоже закрываем экраном блокировки.
  if (policy.mode !== 'off') {
    const tabs = await chrome.tabs.query({});
    for (const tab of tabs) {
      try {
        const url = new URL(tab.url || '');
        if (!/^https?:$/.test(url.protocol)) continue;
        if (isHostBlocked(url.hostname, policy)) {
          await chrome.tabs.update(tab.id, { url: `${BLOCKED_PAGE}?u=${encodeURIComponent(url.hostname)}` });
        }
      } catch {}
    }
  }

  await chrome.action.setBadgeText({ text: policy.mode === 'off' ? '' : 'ON' });
  await chrome.action.setBadgeBackgroundColor({ color: '#2f6f5e' });
}

// ---------- Синхронизация ----------

async function sync() {
  if (!firebaseConfigured()) return;
  const state = await getState();
  if (!state.auth) return;
  try {
    const auth = await freshAuth(state.auth);
    if (auth !== state.auth) await chrome.storage.local.set({ auth });

    const remote = await fetchRemote(auth);
    const patch = {};
    const localConfigTs = state.config.updatedAt || 0;
    if (remote && remote.config && remote.configUpdatedAt > localConfigTs) {
      patch.config = { ...remote.config, updatedAt: remote.configUpdatedAt };
    } else if (!remote || localConfigTs > remote.configUpdatedAt) {
      const ts = localConfigTs || Date.now();
      await pushPart(auth, 'config', { ...state.config, updatedAt: ts }, ts);
    }
    if (remote && remote.sessionUpdatedAt > state.sessionUpdatedAt) {
      patch.session = remote.session;
      patch.sessionUpdatedAt = remote.sessionUpdatedAt;
    } else if (state.sessionUpdatedAt > (remote?.sessionUpdatedAt || 0)) {
      await pushPart(auth, 'session', state.session, state.sessionUpdatedAt);
    }
    await chrome.storage.local.set({ ...patch, lastSync: Date.now(), syncError: '' });
  } catch (e) {
    await chrome.storage.local.set({ syncError: String(e.message || e) });
  }
}

// ---------- Действия из интерфейса ----------

const handlers = {
  async getState() {
    const state = await getState();
    return { ...state, hard: hardActive(state.config, state.session), firebaseConfigured: firebaseConfigured() };
  },

  async startSession({ listIds, minutes, hard }) {
    const { session } = await getState();
    if (sessionActive(session)) throw new Error('Сессия уже идёт');
    if (!listIds?.length) throw new Error('Выберите хотя бы один список');
    const now = Date.now();
    const next = { id: uid(), listIds, startedAt: now, endsAt: now + minutes * 60_000, hard: !!hard, device: 'browser' };
    await chrome.storage.local.set({ session: next, sessionUpdatedAt: now });
    await chrome.alarms.create('sessionEnd', { when: next.endsAt + 500 });
    sync();
    return next;
  },

  async stopSession() {
    const { session } = await getState();
    if (sessionActive(session) && session.hard) throw new Error('Жёсткую сессию нельзя остановить раньше времени');
    await chrome.storage.local.set({ session: null, sessionUpdatedAt: Date.now() });
    sync();
  },

  async saveConfig({ config }) {
    const state = await getState();
    const err = hardModeViolation(state.config, config, state.session);
    if (err) throw new Error(err);
    await chrome.storage.local.set({ config: { ...config, updatedAt: Date.now() } });
    sync();
  },

  async signIn() {
    if (!firebaseConfigured()) throw new Error('Firebase не настроен: заполните extension/config.js');
    const auth = await signInWithGoogle();
    await chrome.storage.local.set({ auth });
    await sync();
    return auth.email;
  },

  async signOut() {
    await chrome.storage.local.remove(['auth', 'lastSync', 'syncError']);
  },

  async syncNow() {
    await sync();
  },
};

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  const fn = handlers[msg?.type];
  if (!fn) return false;
  fn(msg)
    .then((result) => sendResponse({ ok: true, result }))
    .catch((e) => sendResponse({ ok: false, error: String(e.message || e) }));
  return true;
});

// ---------- Жизненный цикл ----------

async function tick() {
  await sync();
  await applyRules();
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.alarms.create('tick', { periodInMinutes: 1 });
  tick();
});
chrome.runtime.onStartup.addListener(() => {
  chrome.alarms.create('tick', { periodInMinutes: 1 });
  tick();
});
chrome.alarms.onAlarm.addListener(() => tick());
chrome.storage.onChanged.addListener((changes) => {
  if (changes.config || changes.session) applyRules();
});
