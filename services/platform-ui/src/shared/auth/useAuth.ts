import { useSyncExternalStore } from "react";
import { getAccessToken, getRoles, subscribeTokens } from "./tokenStore";

const EMPTY_ROLES: string[] = [];

export function useIsAuthenticated() {
  const token = useSyncExternalStore(subscribeTokens, getAccessToken, () => "");
  return token.length > 0;
}

/** Realm roles of the signed-in user (e.g. "user", "operator", "auditor"). */
export function useRoles(): string[] {
  return useSyncExternalStore(subscribeTokens, getRoles, () => EMPTY_ROLES);
}

/** True if the signed-in user has at least one of the given realm roles. */
export function useHasAnyRole(...roles: string[]): boolean {
  const mine = useRoles();
  return roles.some((role) => mine.includes(role));
}
