// Firebase через REST, без SDK: вход Google -> Firebase Auth, документ users/{uid} в Firestore.
import { FIREBASE } from './config.js';

const AUTH = 'https://identitytoolkit.googleapis.com/v1';
const TOKEN = 'https://securetoken.googleapis.com/v1/token';

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error?.message || `HTTP ${res.status}`);
  return data;
}

export async function signInWithGoogle() {
  const redirectUri = chrome.identity.getRedirectURL();
  const nonce = crypto.randomUUID();
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.search = new URLSearchParams({
    client_id: FIREBASE.googleClientId,
    response_type: 'id_token',
    redirect_uri: redirectUri,
    scope: 'openid email profile',
    nonce,
    prompt: 'select_account',
  });
  const result = await chrome.identity.launchWebAuthFlow({ url: url.toString(), interactive: true });
  const idToken = new URLSearchParams(new URL(result).hash.slice(1)).get('id_token');
  if (!idToken) throw new Error('Google не вернул токен');

  const data = await postJson(`${AUTH}/accounts:signInWithIdp?key=${FIREBASE.apiKey}`, {
    postBody: `id_token=${idToken}&providerId=google.com`,
    requestUri: redirectUri,
    returnSecureToken: true,
  });
  return {
    uid: data.localId,
    email: data.email,
    idToken: data.idToken,
    refreshToken: data.refreshToken,
    expiresAt: Date.now() + Number(data.expiresIn) * 1000,
  };
}

export async function freshAuth(auth) {
  if (auth.expiresAt - Date.now() > 60_000) return auth;
  const res = await fetch(`${TOKEN}?key=${FIREBASE.apiKey}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: auth.refreshToken }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error?.message || 'Не удалось обновить вход');
  return {
    ...auth,
    idToken: data.id_token,
    refreshToken: data.refresh_token,
    expiresAt: Date.now() + Number(data.expires_in) * 1000,
  };
}

const docUrl = (uid) =>
  `https://firestore.googleapis.com/v1/projects/${FIREBASE.projectId}/databases/(default)/documents/users/${uid}`;

// Возвращает { config, configUpdatedAt, session, sessionUpdatedAt } или null, если документа ещё нет.
export async function fetchRemote(auth) {
  const res = await fetch(docUrl(auth.uid), { headers: { Authorization: `Bearer ${auth.idToken}` } });
  if (res.status === 404) return null;
  const data = await res.json();
  if (!res.ok) throw new Error(data.error?.message || `HTTP ${res.status}`);
  const f = data.fields || {};
  const json = (k) => (f[k]?.stringValue ? JSON.parse(f[k].stringValue) : null);
  const int = (k) => Number(f[k]?.integerValue || 0);
  return {
    config: json('config'),
    configUpdatedAt: int('configUpdatedAt'),
    session: json('session'),
    sessionUpdatedAt: int('sessionUpdatedAt'),
  };
}

// part: 'config' или 'session'
export async function pushPart(auth, part, value, updatedAt) {
  const url = new URL(docUrl(auth.uid));
  url.searchParams.append('updateMask.fieldPaths', part);
  url.searchParams.append('updateMask.fieldPaths', `${part}UpdatedAt`);
  const res = await fetch(url, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${auth.idToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      fields: {
        [part]: { stringValue: JSON.stringify(value) },
        [`${part}UpdatedAt`]: { integerValue: String(updatedAt) },
      },
    }),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error?.message || `HTTP ${res.status}`);
  }
}
