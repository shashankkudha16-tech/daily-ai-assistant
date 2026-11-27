import { NavLink } from 'react-router-dom';
import { HomeIcon, CompassIcon, BookIcon, BookmarkIcon, UserIcon } from './Icons.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

const items = [
  { to: '/', label: 'Dashboard', Icon: HomeIcon, end: true },
  { to: '/mentor', label: 'AI Mentor', Icon: UserIcon },
  { to: '/discover', label: 'Daily News', Icon: CompassIcon },
  { to: '/learn', label: 'Learning Plan', Icon: BookIcon },
  { to: '/progress', label: 'My Progress', Icon: UserIcon },
  { to: '/history', label: 'History', Icon: BookmarkIcon },
  { to: '/settings', label: 'Settings', Icon: UserIcon },
];

export default function BottomNav() {
  const { user } = useAuth();
  const visibleItems = user ? items : items.slice(0, 2);
  return (
    <nav className="bottom-nav" aria-label="Main navigation">
      {visibleItems.map(({ to, label, Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
        >
          <Icon />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
