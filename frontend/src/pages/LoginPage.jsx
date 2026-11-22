import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { apiErrorMessage } from '../api/client.js';

const registrationEnabled = import.meta.env.VITE_REGISTRATION_ENABLED !== 'false';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const result = await login(email, password);
      const destination = location.state?.from?.pathname || '/';
      navigate(destination, { replace: true, state: { guestMigration: result.guestMigration } });
    } catch (err) {
      setError(apiErrorMessage(err) || 'Could not sign in. Check your details and try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-shell">
      <main className="auth-card">
        <div className="auth-brand">
          <span className="brand-mark" aria-hidden="true">AI</span>
          <div>
            <strong>Daily AI</strong>
            <span className="brand-subtitle">Updates &amp; learning</span>
          </div>
        </div>

        <div>
          <h1>Welcome back</h1>
          <p className="muted">Sign in to see your private bookmarks, reading history and learning progress.</p>
        </div>

        <form className="auth-form" onSubmit={submit} noValidate>
          <label className="field">
            <span>Email</span>
            <input
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              maxLength={200}
            />
          </label>

          <label className="field">
            <span>Password</span>
            <div className="password-field">
              <input
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
                maxLength={72}
              />
              <button
                type="button"
                className="password-toggle"
                onClick={() => setShowPassword((value) => !value)}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
          </label>

          {error && <p className="notice error" role="alert">{error}</p>}

          <button type="submit" className="button auth-submit" disabled={busy || !email || !password}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        {registrationEnabled && (
          <p className="muted small auth-switch">
            New here? <Link className="text-link" to="/register">Create an account</Link>
          </p>
        )}
        <p className="muted small">
          Your session is kept in an HttpOnly cookie. Your password is never stored in the browser.
        </p>
      </main>
    </div>
  );
}
