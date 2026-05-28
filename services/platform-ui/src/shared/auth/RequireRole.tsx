import type { ReactNode } from "react";
import { RequireAuth } from "./RequireAuth";
import { useRoles } from "./useAuth";

/**
 * Gates a route on realm roles. Renders a friendly "restricted" notice when the signed-in user
 * lacks every required role — instead of letting the call reach the API and 403.
 * Composes RequireAuth, so unauthenticated users are still redirected to sign in first.
 */
export function RequireRole({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  return (
    <RequireAuth>
      <RoleGate anyOf={anyOf}>{children}</RoleGate>
    </RequireAuth>
  );
}

function RoleGate({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  const roles = useRoles();
  const allowed = anyOf.some((role) => roles.includes(role));
  if (!allowed) {
    return (
      <section className="card">
        <h2>Restricted area 🔒</h2>
        <p className="muted">
          This section requires the <strong>{anyOf.join(" or ")}</strong> role.
        </p>
        <p className="muted">
          You are signed in as{" "}
          <strong>{roles.length ? roles.join(", ") : "a standard user"}</strong>. Sign in with an
          account that has the required role to view it.
        </p>
      </section>
    );
  }
  return <>{children}</>;
}
