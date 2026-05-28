import { StatusBanner } from "../../../shared/ui/StatusBanner";
import { TokenInput } from "../../../shared/ui/TokenInput";

export function AdminPage() {
  return (
    <>
      <section className="card">
        <h2>Service health</h2>
        <p className="muted">Probes gateway and downstream services. 401/403 still counts as reachable.</p>
        <StatusBanner />
      </section>

      <section className="card">
        <h2>Manual tokens</h2>
        <p className="muted">
          Paste tokens issued by another tool (curl, Postman). Access token is held in memory only;
          refresh token is in <code>sessionStorage</code> and cleared when the tab closes.
        </p>
        <TokenInput />
      </section>
    </>
  );
}
