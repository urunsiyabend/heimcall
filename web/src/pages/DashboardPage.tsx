import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

// Placeholder landing for Slice 0/1. The incident list (Slice 2) renders here next.
export function DashboardPage() {
  const { memberships, activeOrgId } = useAuth()
  const active = memberships.find((m) => m.organizationId === activeOrgId)

  if (memberships.length === 0) {
    return (
      <div className="panel">
        <h2>Welcome</h2>
        <p>You are not a member of any organization yet.</p>
        <Link to="/orgs/new" className="btn-link">
          Create your first organization
        </Link>
      </div>
    )
  }

  return (
    <div className="panel">
      <h2>{active?.organizationName ?? 'Dashboard'}</h2>
      <p className="hint">Active org: {activeOrgId}</p>
      <p>Incident list lands here in Slice 2.</p>
    </div>
  )
}
