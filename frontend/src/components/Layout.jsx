import { Link, Outlet } from 'react-router-dom';
import BottomNav from './BottomNav.jsx';
import { MoonIcon, SunIcon } from './Icons.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

export default function Layout({ theme, onToggleTheme }) {
  const { user, guestMigrationStatus: migrationStatus } = useAuth();
  const migrationMessage = ({
    MIGRATED: 'Your reading interests were added to your account.',
    NO_DATA: 'You are signed in; there were no guest interests to transfer.',
    ALREADY_MIGRATED: 'Your reading interests were already transferred.',
    FAILED: 'Your account is ready, but guest interests could not be transferred.',
  })[migrationStatus];
  return (
    <div className="app-shell">
      <header className="top-bar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            AI
          </span>
          <div>
            <strong>Daily AI</strong>
            <span className="brand-subtitle">Updates &amp; learning</span>
          </div>
        </div>
        {!user && <div className="auth-actions"><Link to="/login">Log in</Link><Link className="button small" to="/register">Sign up</Link></div>}
        <button
          type="button"
          className="icon-button"
          onClick={onToggleTheme}
          aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
        >
          {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
        </button>
      </header>

      <main className="page-content">
        {migrationMessage && <p className={`notice ${migrationStatus === 'FAILED' ? 'error' : 'success'}`} role="status">{migrationMessage}</p>}
        {!user && <p className="guest-notice">Browsing as a guest. Sign in to access learning progress and saved items.</p>}
        <Outlet />
      </main>

      <BottomNav />
    </div>
  );
}
