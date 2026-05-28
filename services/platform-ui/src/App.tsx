import { NavLink, Route, Routes } from 'react-router-dom'
import { AccountingPage } from './features/accounting/pages/AccountingPage'
import { AccountsPage } from './features/accounts/pages/AccountsPage'
import { AdminPage } from './features/admin/pages/AdminPage'
import { AuthPage } from './features/auth/pages/AuthPage'
import { HomePage } from './features/home/pages/HomePage'
import { TransactionsPage } from './features/transactions/pages/TransactionsPage'
import { RequireAuth } from './shared/auth/RequireAuth'
import { RequireRole } from './shared/auth/RequireRole'
import { useHasAnyRole } from './shared/auth/useAuth'
import { UserMenu } from './shared/ui/UserMenu'

// Roles that may use the accounting / audit module (mirrors the backend @PreAuthorize gates).
const ACCOUNTING_ROLES = ['auditor', 'operator']

export default function App() {
  const canAccounting = useHasAnyRole(...ACCOUNTING_ROLES)

  return (
    <div className="layout">
      <header className="topbar">
        <div className="topbar__brand">
          <span className="topbar__logo" aria-hidden>◆</span>
          <span className="topbar__title">FinTech Platform</span>
        </div>
        <UserMenu />
      </header>

      <nav className="nav" aria-label="Primary">
        <NavLink to="/" end>Home</NavLink>
        <NavLink to="/accounts">Accounts</NavLink>
        <NavLink to="/transactions">Transactions</NavLink>
        {/* Visible to all (it's a test console), but flagged when the user lacks the role. */}
        <NavLink
          to="/accounting"
          title={canAccounting ? undefined : 'Requires the auditor or operator role'}
        >
          Accounting{canAccounting ? '' : ' 🔒'}
        </NavLink>
        <NavLink to="/admin" className="nav__admin">Admin</NavLink>
      </nav>

      <main className="content">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/auth" element={<AuthPage />} />
          <Route
            path="/accounts"
            element={<RequireAuth><AccountsPage /></RequireAuth>}
          />
          <Route
            path="/transactions"
            element={<RequireAuth><TransactionsPage /></RequireAuth>}
          />
          <Route
            path="/accounting"
            element={<RequireRole anyOf={ACCOUNTING_ROLES}><AccountingPage /></RequireRole>}
          />
          <Route path="/admin" element={<AdminPage />} />
        </Routes>
      </main>
    </div>
  )
}
