import { useSyncExternalStore } from "react";
import { getAccessToken, subscribeTokens } from "./tokenStore";

export function useIsAuthenticated() {
  const token = useSyncExternalStore(subscribeTokens, getAccessToken, () => "");
  return token.length > 0;
}
