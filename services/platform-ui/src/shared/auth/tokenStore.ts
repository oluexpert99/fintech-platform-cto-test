const REFRESH_TOKEN_KEY = "platform-ui-refresh-token";

const EMPTY_ROLES: string[] = [];

let accessTokenMemory = "";
let rolesMemory: string[] = EMPTY_ROLES;
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) listener();
}

/** Decode the JWT payload (no signature check) and pull realm_access.roles. */
function decodeRoles(token: string): string[] {
  if (!token) return EMPTY_ROLES;
  const parts = token.split(".");
  if (parts.length < 2) return EMPTY_ROLES;
  try {
    const json = atob(parts[1].replace(/-/g, "+").replace(/_/g, "/"));
    const payload = JSON.parse(json);
    const roles = payload?.realm_access?.roles;
    return Array.isArray(roles) ? roles : EMPTY_ROLES;
  } catch {
    return EMPTY_ROLES;
  }
}

export function getAccessToken() {
  return accessTokenMemory;
}

/** Realm roles from the current access token. Stable reference between token changes. */
export function getRoles(): string[] {
  return rolesMemory;
}

export function setAccessToken(token: string) {
  accessTokenMemory = token ?? "";
  rolesMemory = decodeRoles(accessTokenMemory);
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
  rolesMemory = EMPTY_ROLES;
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
