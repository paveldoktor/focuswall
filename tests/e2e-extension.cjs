// Загружает расширение в Chromium и проверяет блокировку в обоих режимах.
const { chromium } = require('playwright');
const path = require('path');
const os = require('os');
const fs = require('fs');

(async () => {
  const ext = path.resolve(__dirname, '../extension');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fw-'));
  const ctx = await chromium.launchPersistentContext(dir, {
    channel: 'chromium',
    headless: true,
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
  });
  let [sw] = ctx.serviceWorkers();
  if (!sw) sw = await ctx.waitForEvent('serviceworker');
  const id = sw.url().split('/')[2];
  const page = await ctx.newPage();
  await page.goto(`chrome-extension://${id}/popup.html`);
  await page.waitForTimeout(500);
  const send = (type, payload = {}) => page.evaluate(([t, p]) => chrome.runtime.sendMessage({ type: t, ...p }), [type, payload]);
  const visit = async (url) => {
    const p = await ctx.newPage();
    await p.goto(url).catch(() => {});
    await p.waitForTimeout(300);
    const u = p.url();
    await p.close();
    return u;
  };

  console.log('start black-list session:', (await send('startSession', { listIds: ['social'], minutes: 30 })).ok);
  await page.waitForTimeout(500);
  console.log('vk.com ->', await visit('https://vk.com/feed'));
  await page.screenshot({ path: process.argv[2] + '/popup.png' });
  console.log('stop:', (await send('stopSession')).ok);
  await page.waitForTimeout(500);

  console.log('start white-list session (hard):', (await send('startSession', { listIds: ['night'], minutes: 30, hard: true })).ok);
  await page.waitForTimeout(500);
  console.log('example.org ->', await visit('https://example.org/'));
  console.log('stop hard:', JSON.stringify(await send('stopSession')));
  const bp = await ctx.newPage();
  await bp.goto('https://example.org/').catch(() => {});
  await bp.waitForTimeout(500);
  await bp.setViewportSize({ width: 900, height: 600 });
  await bp.screenshot({ path: process.argv[2] + '/blocked.png' });
  await page.reload(); await page.waitForTimeout(500);
  await page.screenshot({ path: process.argv[2] + '/popup-hard.png' });
  const op = await ctx.newPage();
  await op.setViewportSize({ width: 900, height: 1100 });
  await op.goto(`chrome-extension://${id}/options.html`); await op.waitForTimeout(500);
  await op.screenshot({ path: process.argv[2] + '/options.png' });
  await ctx.close();
})();
