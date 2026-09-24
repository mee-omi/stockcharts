import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import {
  AUTH_REQUIRED_EVENT,
  apiErrorMessage,
  changePassword,
  getAuthStatus,
  login,
  logout,
  setupAccount,
} from '../api/client'
import type { AuthStatus } from '../api/types'

const AuthContext = createContext<{ status: AuthStatus | null; signOut: () => void }>({
  status: null,
  signOut: () => {},
})

/** Renders the app only once the user is signed in (or auth is disabled on the server). */
export default function AuthGate({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(() => {
    getAuthStatus().then((s) => { setStatus(s); setError(null) })
      .catch((e) => setError(apiErrorMessage(e, 'Backend unreachable')))
  }, [])

  useEffect(() => { refresh() }, [refresh])
  useEffect(() => {
    const onExpired = () => setStatus((s) => (s && s.authEnabled ? { ...s, authenticated: false } : s))
    window.addEventListener(AUTH_REQUIRED_EVENT, onExpired)
    return () => window.removeEventListener(AUTH_REQUIRED_EVENT, onExpired)
  }, [])

  const signOut = useCallback(() => {
    logout().finally(refresh)
  }, [refresh])

  if (!status) {
    return (
      <div className="auth-screen">
        <div className="auth-card">
          <h1 className="auth-title">StockCharts</h1>
          {error
            ? <><div className="auth-error">{error}</div><button className="auth-submit" onClick={refresh}>Retry</button></>
            : <div className="auth-sub">Loading…</div>}
        </div>
      </div>
    )
  }
  if (status.authEnabled && !status.authenticated) {
    return <LoginScreen setup={status.setupRequired} onDone={setStatus} />
  }
  return <AuthContext.Provider value={{ status, signOut }}>{children}</AuthContext.Provider>
}

function LoginScreen({ setup, onDone }: { setup: boolean; onDone: (s: AuthStatus) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (setup && password !== confirm) { setError('Passwords do not match'); return }
    setBusy(true)
    setError(null)
    try {
      onDone(setup ? await setupAccount(username, password) : await login(username, password))
    } catch (err: any) {
      setError(err?.response?.status === 401 ? 'Wrong username or password'
        : err?.response?.status === 429 ? 'Too many attempts — wait a minute and try again'
          : apiErrorMessage(err, 'Sign-in failed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-screen">
      <form className="auth-card" onSubmit={submit}>
        <h1 className="auth-title">StockCharts</h1>
        <div className="auth-sub">
          {setup ? 'Create the account that protects this app (charts, broker keys, orders).' : 'Sign in to continue'}
        </div>
        <label className="auth-label">Username
          <input className="of-input" autoComplete="username" value={username} autoFocus
                 onChange={(e) => setUsername(e.target.value)} />
        </label>
        <label className="auth-label">Password
          <input className="of-input" type="password" autoComplete={setup ? 'new-password' : 'current-password'}
                 value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {setup && (
          <label className="auth-label">Confirm password
            <input className="of-input" type="password" autoComplete="new-password" value={confirm}
                   onChange={(e) => setConfirm(e.target.value)} />
          </label>
        )}
        {setup && <div className="auth-hint">At least 10 characters.</div>}
        {error && <div className="auth-error" role="alert">{error}</div>}
        <button className="auth-submit" type="submit" disabled={busy || !username || !password}>
          {busy ? '…' : setup ? 'Create account' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}

/** Header control: signed-in user, change password, sign out. Hidden when auth is disabled. */
export function UserMenu() {
  const { status, signOut } = useContext(AuthContext)
  const [open, setOpen] = useState(false)
  const [changing, setChanging] = useState(false)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)

  if (!status?.authEnabled || !status.username) return null

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await changePassword(current, next)
      setMsg({ ok: true, text: 'Password changed — other sessions were signed out' })
      setCurrent(''); setNext(''); setChanging(false)
    } catch (err) {
      setMsg({ ok: false, text: apiErrorMessage(err, 'Could not change password') })
    }
  }

  return (
    <div className="user-menu">
      <button className="panel-btn" onClick={() => { setOpen((o) => !o); setMsg(null) }}
              title={`Signed in as ${status.username}`} aria-label={`Account: ${status.username}`}>
        👤
      </button>
      {open && (
        <div className="user-menu-pop">
          <div className="user-menu-name">Signed in as <strong>{status.username}</strong></div>
          {changing ? (
            <form onSubmit={submit} className="user-menu-form">
              <input className="of-input" type="password" placeholder="Current password" autoComplete="current-password"
                     value={current} onChange={(e) => setCurrent(e.target.value)} />
              <input className="of-input" type="password" placeholder="New password (10+ chars)"
                     autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
              <div className="user-menu-actions">
                <button type="button" className="panel-btn" onClick={() => setChanging(false)}>Cancel</button>
                <button type="submit" className="panel-btn" disabled={!current || !next}>Save</button>
              </div>
            </form>
          ) : (
            <>
              <button className="user-menu-item" onClick={() => setChanging(true)}>Change password</button>
              <button className="user-menu-item" onClick={signOut}>Sign out</button>
            </>
          )}
          {msg && <div className={`ci-status ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</div>}
        </div>
      )}
    </div>
  )
}
