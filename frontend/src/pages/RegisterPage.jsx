import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { apiErrorMessage } from '../api/client.js';

const registrationEnabled = import.meta.env.VITE_REGISTRATION_ENABLED !== 'false';

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (!registrationEnabled) {
    return (
      <div className="auth-shell">
        <main className="auth-card">
          <div className="auth-brand">
            <span className="brand-mark" aria-hidden="true">AI</span>
            <div><strong>Daily AI</strong><span className="brand-subtitle">Updates &amp; learning</span></div>
          </div>
          <h1>Personal application</h1>
          <p className="muted">New account registration is disabled.</p>
          <Link className="button auth-submit" to="/login">Sign in</Link>
        </main>
      </div>
    );
  }

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await register({ name, email, password, confirmPassword });
      navigate('/', { replace: true, state: { guestMigration: result.guestMigration } });
    } catch (err) {
      setError(apiErrorMessage(err));
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
          <h1>Create your account</h1>
          <p className="muted">Your profile, bookmarks, history and progress stay private to you.</p>
        </div>

        <form className="auth-form" onSubmit={submit} noValidate>
          <label className="field">
            <span>Full name</span>
            <input
              type="text"
              autoComplete="name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              maxLength={120}
            />
          </label>

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
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
                minLength={10}
                maxLength={72}
                aria-describedby="password-guidance"
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
            <span id="password-guidance" className="muted small">
              At least 10 characters with upper and lower case letters, a number and a symbol.
            </span>
          </label>

          <label className="field">
            <span>Confirm password</span>
            <input
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              required
              minLength={10}
              maxLength={72}
            />
          </label>

          {error && <p className="notice error" role="alert">{error}</p>}

          <button
            type="submit"
            className="button auth-submit"
            disabled={busy || !name || !email || !password || !confirmPassword}
          >
            {busy ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="muted small auth-switch">
          Already have an account? <Link className="text-link" to="/login">Sign in</Link>
        </p>
      </main>
    </div>
  );
}
