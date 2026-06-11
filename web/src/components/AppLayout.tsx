import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export function AppLayout() {
  const { user, memberships, activeOrgId, setActiveOrg, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">
          Heimcall
        </Link>
        <div className="spacer" />
        {memberships.length > 0 ? (
          <select
            value={activeOrgId ?? ''}
            onChange={(e) => setActiveOrg(e.target.value)}
            aria-label="Active organization"
          >
            {memberships.map((m) => (
              <option key={m.organizationId} value={m.organizationId}>
                {m.organizationName} ({m.role})
              </option>
            ))}
          </select>
        ) : (
          <span className="hint">no organizations yet</span>
        )}
        <Link to="/orgs/new" className="btn-link">
          + New org
        </Link>
        <span className="user">{user?.displayName}</span>
        <button
          onClick={() => {
            logout()
            navigate('/login')
          }}
        >
          Sign out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
