import assert from 'node:assert/strict';
import test from 'node:test';
import {
  blockedUntil, defaultConfig, hardModeViolation, isHostBlocked, normalizeDomain, scheduleActive, sitePolicy,
} from '../extension/core.js';

const at = (iso) => new Date(iso); // локальное время

test('normalizeDomain', () => {
  assert.equal(normalizeDomain('https://www.YouTube.com/watch?v=1'), 'youtube.com');
  assert.equal(normalizeDomain('*.vk.com'), 'vk.com');
  assert.equal(normalizeDomain('не сайт'), '');
});

test('ночное расписание 21:00–05:00 через полночь', () => {
  const s = defaultConfig().schedules[0];
  assert.equal(scheduleActive(s, at('2026-10-27T20:59:00')), false);
  assert.equal(scheduleActive(s, at('2026-10-27T21:00:00')), true);
  assert.equal(scheduleActive(s, at('2026-10-28T03:00:00')), true);
  assert.equal(scheduleActive(s, at('2026-10-28T05:00:00')), false);
});

test('ночью сайты в режиме белого списка', () => {
  const c = defaultConfig();
  const p = sitePolicy(c, null, at('2026-10-27T23:00:00'));
  assert.equal(p.mode, 'allow');
  assert.equal(isHostBlocked('youtube.com', p), true);
  assert.equal(sitePolicy(c, null, at('2026-10-27T12:00:00')).mode, 'off');
});

test('сессия с чёрным списком', () => {
  const c = defaultConfig();
  const now = at('2026-10-27T12:00:00');
  const session = { listIds: ['social'], endsAt: now.getTime() + 60_000 };
  const p = sitePolicy(c, session, now);
  assert.equal(isHostBlocked('m.vk.com', p), true);
  assert.equal(isHostBlocked('wikipedia.org', p), false);
});

test('блокировка заканчивается в 05:00', () => {
  const until = blockedUntil(defaultConfig(), null, at('2026-10-27T23:00:00'));
  assert.equal(new Date(until).getHours(), 5);
});

test('жёсткое расписание нельзя ослабить ночью', () => {
  const c = defaultConfig();
  const night = at('2026-10-27T23:00:00');
  const loosened = structuredClone(c);
  loosened.blocklists[0].domains.push('youtube.com');
  assert.ok(hardModeViolation(c, loosened, null, night));
  const disabled = structuredClone(c);
  disabled.schedules[0].enabled = false;
  assert.ok(hardModeViolation(c, disabled, null, night));
  assert.equal(hardModeViolation(c, disabled, null, at('2026-10-27T12:00:00')), null);
});
