import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useIsAuthenticated } from "./useAuth";

export function RequireAuth({ children }: { children: ReactNode }) {
  const authed = useIsAuthenticated();
  const location = useLocation();
  if (!authed) {
    return <Navigate to="/auth" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
