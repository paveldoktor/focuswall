import { blockedUntil, formatLeft } from './core.js';

const host = new URLSearchParams(location.search).get('u') || 'Этот сайт';
document.getElementById('host').textContent = host;

async function update() {
  const { config, session } = await chrome.storage.local.get(['config', 'session']);
  const until = config ? blockedUntil(config, session || null) : null;
  document.getElementById('left').textContent = until
    ? `Блокировка закончится через ${formatLeft(until - Date.now())}.`
    : 'Блокировка уже закончилась, можно обновить страницу.';
}
update();
setInterval(update, 30_000);
