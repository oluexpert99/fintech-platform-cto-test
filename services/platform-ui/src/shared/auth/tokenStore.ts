const REFRESH_TOKEN_KEY = "platform-ui-refresh-token";

let accessTokenMemory = "";
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) listener();
}

export function getAccessToken() {
  return accessTokenMemory;
}

export function setAccessToken(token: string) {
  accessTokenMemory = token ?? "";
  emit();
}

export function getRefreshToken() {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY) || "";
}

export function setRefreshToken(token: string) {
  if (!token) {
    sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  } else {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, token);
  }
  emit();
}

export function clearTokens() {
  accessTokenMemory = "";
  sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  emit();
}

export function subscribeTokens(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function hasAccessToken() {
  return accessTokenMemory.length > 0;
}
