import { Link } from "react-router-dom";
import { useIsAuthenticated } from "../../../shared/auth/useAuth";

export function HomePage() {
  const authed = useIsAuthenticated();

  return (
    <section className="card">
      <h2>FinTech platform</h2>
      <p>
        Integration console for accounts, transactions, and accounting,
        wired through the gateway.
      </p>
      <ul className="link-list">
        <li><Link to="/accounts">Accounts</Link> — open, list, freeze, balance</li>
        <li><Link to="/transactions">Transactions</Link> — transfer, reversal, lookup</li>
        <li><Link to="/accounting">Accounting</Link> — journal, chart of accounts, trial balance</li>
      </ul>
      {!authed && (
        <p className="muted">
          <Link to="/auth">Sign in</Link> to use the platform.
        </p>
      )}
    </section>
  );
}
