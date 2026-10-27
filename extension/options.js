import { send, esc } from './api.js';
import { DAY_NAMES, normalizeDomain, parseList, uid } from './core.js';

const $ = (id) => document.getElementById(id);
let config;

// Порядок дней в интерфейсе: с понедельника.
const DAY_ORDER = [1, 2, 3, 4, 5, 6, 0];

function listCard(l) {
  return `<div class="card stack" data-list="${esc(l.id)}">
    <div class="row">
      <input type="text" class="name" data-k="name" value="${esc(l.name)}" />
    </div>
    <div class="row">
      <select data-k="mode">
        <option value="block" ${l.mode !== 'allow' ? 'selected' : ''}>Блокировать перечисленное</option>
        <option value="allow" ${l.mode === 'allow' ? 'selected' : ''}>Блокировать всё, кроме перечисленного</option>
      </select>
      <button class="danger small" data-del-list>Удалить</button>
    </div>
    <div class="grid">
      <label class="field"><span>Сайты, по одному в строке</span>
        <textarea data-k="domains" placeholder="youtube.com">${esc(l.domains.join('\n'))}</textarea></label>
      <label class="field"><span>Приложения Android (имя пакета)</span>
        <textarea data-k="androidApps" placeholder="com.instagram.android">${esc((l.androidApps || []).join('\n'))}</textarea></label>
      <label class="field"><span>Программы на компьютере (имя .exe)</span>
        <textarea data-k="desktopApps" placeholder="telegram.exe">${esc((l.desktopApps || []).join('\n'))}</textarea></label>
    </div>
  </div>`;
}

function scheduleCard(s) {
  const lists = config.blocklists
    .map((l) => `<label class="check"><input type="checkbox" data-sl value="${esc(l.id)}" ${s.listIds.includes(l.id) ? 'checked' : ''}/> ${esc(l.name)}</label>`)
    .join(' ');
  const days = DAY_ORDER
    .map((d) => `<label class="check"><input type="checkbox" data-day value="${d}" ${s.days.includes(d) ? 'checked' : ''}/> ${DAY_NAMES[d]}</label>`)
    .join(' ');
  return `<div class="card stack" data-schedule="${esc(s.id)}">
    <div class="row spread">
      <input type="text" class="name" style="flex:1" data-k="name" value="${esc(s.name)}" />
      <button class="danger small" data-del-schedule>Удалить</button>
    </div>
    <div class="row">
      с <input type="time" data-k="start" value="${esc(s.start)}" /> до <input type="time" data-k="end" value="${esc(s.end)}" />
      <label class="check"><input type="checkbox" data-k="enabled" ${s.enabled ? 'checked' : ''}/> включено</label>
      <label class="check"><input type="checkbox" data-k="hard" ${s.hard ? 'checked' : ''}/> жёсткий режим</label>
    </div>
    <div class="row">${days}</div>
    <div class="row small">Списки: ${lists}</div>
  </div>`;
}

function render() {
  $('lists').innerHTML = config.blocklists.map(listCard).join('');
  $('schedules').innerHTML = config.schedules.map(scheduleCard).join('') || '<p class="muted">Расписаний нет.</p>';
}

// Собираем конфиг обратно из формы.
function readForm() {
  const blocklists = [...document.querySelectorAll('[data-list]')].map((el) => {
    const get = (k) => el.querySelector(`[data-k="${k}"]`).value;
    return {
      id: el.dataset.list,
      name: get('name').trim() || 'Без названия',
      mode: get('mode'),
      domains: parseList(get('domains'), normalizeDomain),
      androidApps: parseList(get('androidApps')),
      desktopApps: parseList(get('desktopApps'), (s) => s.trim().toLowerCase()),
    };
  });
  const schedules = [...document.querySelectorAll('[data-schedule]')].map((el) => {
    const q = (k) => el.querySelector(`[data-k="${k}"]`);
    return {
      id: el.dataset.schedule,
      name: q('name').value.trim() || 'Расписание',
      start: q('start').value || '00:00',
      end: q('end').value || '00:00',
      enabled: q('enabled').checked,
      hard: q('hard').checked,
      days: [...el.querySelectorAll('[data-day]:checked')].map((i) => Number(i.value)),
      listIds: [...el.querySelectorAll('[data-sl]:checked')].map((i) => i.value),
    };
  });
  return { ...config, blocklists, schedules };
}

function msg(text, isError) {
  $('msg').textContent = text;
  $('msg').className = isError ? 'small error' : 'small muted';
}

document.addEventListener('click', (e) => {
  if (e.target.matches('[data-del-list]')) {
    config = readForm();
    const id = e.target.closest('[data-list]').dataset.list;
    config.blocklists = config.blocklists.filter((l) => l.id !== id);
    config.schedules.forEach((s) => (s.listIds = s.listIds.filter((x) => x !== id)));
    render();
  }
  if (e.target.matches('[data-del-schedule]')) {
    config = readForm();
    const id = e.target.closest('[data-schedule]').dataset.schedule;
    config.schedules = config.schedules.filter((s) => s.id !== id);
    render();
  }
});

$('addList').onclick = () => {
  config = readForm();
  config.blocklists.push({ id: uid(), name: 'Новый список', mode: 'block', domains: [], androidApps: [], desktopApps: [] });
  render();
};
$('addSchedule').onclick = () => {
  config = readForm();
  config.schedules.push({ id: uid(), name: 'Рабочее время', listIds: [], days: [1, 2, 3, 4, 5], start: '09:00', end: '18:00', enabled: true, hard: false });
  render();
};
$('save').onclick = async () => {
  try {
    await send('saveConfig', { config: readForm() });
    config = (await send('getState')).config;
    render();
    msg('Сохранено');
  } catch (e) {
    msg(e.message, true);
  }
};

(async () => {
  config = (await send('getState')).config;
  render();
})();
