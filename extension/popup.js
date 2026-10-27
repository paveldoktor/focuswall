import { send, esc } from './api.js';
import { blockedUntil, formatLeft, scheduleActive, sessionActive } from './core.js';

const $ = (id) => document.getElementById(id);

async function render() {
  const st = await send('getState');
  const { config, session } = st;
  const now = new Date();
  const running = sessionActive(session);
  const schedules = config.schedules.filter((s) => scheduleActive(s, now));
  const until = blockedUntil(config, session, now);
  const names = (ids) => ids.map((id) => config.blocklists.find((l) => l.id === id)?.name).filter(Boolean).join(', ');

  let html;
  if (until) {
    const parts = [];
    if (running) parts.push(`Сессия: ${esc(names(session.listIds))}`);
    schedules.forEach((s) => parts.push(`Расписание «${esc(s.name)}»: ${esc(names(s.listIds))}`));
    html = `<div class="row spread"><span class="pill">Блокировка включена</span><b>ещё ${formatLeft(until - now)}</b></div>
      <div class="small muted" style="margin-top:6px">${parts.join('<br>')}</div>`;
    if (running && !st.hard) html += `<button id="stop" class="danger" style="margin-top:10px">Остановить сессию</button>`;
    if (st.hard) html += `<div class="small muted" style="margin-top:6px">Жёсткий режим: отключить до конца нельзя.</div>`;
  } else {
    const next = config.schedules.filter((s) => s.enabled).map((s) => `${esc(s.name)} ${s.start}–${s.end}`);
    html = `<span class="pill" style="background:transparent;border:1px solid var(--line);color:var(--muted)">Блокировка выключена</span>
      ${next.length ? `<div class="small muted" style="margin-top:6px">Расписание: ${next.join(', ')}</div>` : ''}`;
  }
  $('status').innerHTML = html;
  $('stop')?.addEventListener('click', () => act(() => send('stopSession')));

  $('startCard').style.display = running ? 'none' : '';
  $('lists').innerHTML = config.blocklists
    .map((l) => `<label class="check"><input type="checkbox" value="${esc(l.id)}" ${l.mode === 'allow' ? '' : 'checked'} />
      ${esc(l.name)} <span class="small muted">${l.mode === 'allow' ? 'только разрешённое' : `${l.domains.length} сайтов`}</span></label>`)
    .join('');

  if (!st.firebaseConfigured) {
    $('account').innerHTML = `<span class="small muted">Синхронизация не настроена, всё хранится в этом браузере.</span>`;
  } else if (st.auth) {
    const sync = st.syncError ? `<span class="error">ошибка синхронизации: ${esc(st.syncError)}</span>`
      : st.lastSync ? `синхронизировано ${new Date(st.lastSync).toLocaleTimeString()}` : '';
    $('account').innerHTML = `<div class="small"><b>${esc(st.auth.email)}</b><br><span class="muted">${sync}</span></div>
      <button id="signOut">Выйти</button>`;
    $('signOut').onclick = () => act(() => send('signOut'));
  } else {
    $('account').innerHTML = `<span class="small muted">Войдите, чтобы синхронизировать с телефоном</span>
      <button id="signIn" class="primary">Войти через Google</button>`;
    $('signIn').onclick = () => act(() => send('signIn'));
  }
}

async function act(fn) {
  $('error').textContent = '';
  try {
    await fn();
  } catch (e) {
    $('error').textContent = e.message;
  }
  await render();
}

$('start').onclick = () =>
  act(() =>
    send('startSession', {
      listIds: [...document.querySelectorAll('#lists input:checked')].map((i) => i.value),
      minutes: Number($('minutes').value),
      hard: $('hard').checked,
    }),
  );

render();
setInterval(render, 30_000);
