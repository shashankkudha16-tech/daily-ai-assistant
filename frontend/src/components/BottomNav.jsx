import { NavLink } from 'react-router-dom';
import { HomeIcon, CompassIcon, BookIcon, BookmarkIcon, UserIcon } from './Icons.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

const items = [
  { to: '/', label: 'Home', Icon: HomeIcon, end: true },
  { to: '/discover', label: 'Discover', Icon: CompassIcon },
  { to: '/learn', label: 'Learn', Icon: BookIcon },
  { to: '/bookmarks', label: 'Saved', Icon: BookmarkIcon },
  { to: '/profile', label: 'Profile', Icon: UserIcon },
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
