import { STORAGE_KEYS } from "../config.js";

export function loadSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.session);
    return raw ? JSON.parse(raw) : null;
  } catch (error) {
    return null;
  }
}

export function saveSession(session) {
  localStorage.setItem(STORAGE_KEYS.session, JSON.stringify(session));
}

export function clearSession() {
  localStorage.removeItem(STORAGE_KEYS.session);
}
