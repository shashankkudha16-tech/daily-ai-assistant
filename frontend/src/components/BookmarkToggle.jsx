import { useEffect, useState } from 'react';
import { addBookmark, removeBookmark } from '../api/api.js';
import { BookmarkIcon } from './Icons.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

export default function BookmarkToggle({ itemType, itemId, initial = false, onChange }) {
  const { user } = useAuth();
  const [saved, setSaved] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setSaved(initial);
  }, [initial, itemId]);

  async function toggle(event) {
    event.preventDefault();
    event.stopPropagation();
    if (busy) return;
    setBusy(true);
    setError('');
    if (!user) {
      setError('Log in to save articles');
      setBusy(false);
      return;
    }
    const next = !saved;
    setSaved(next);
    try {
      if (next) {
        await addBookmark(itemType, itemId);
      } else {
        await removeBookmark(itemType, itemId);
      }
      onChange?.(next);
    } catch {
      setSaved(!next);
      setError('Could not update bookmark');
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      className={`bookmark-toggle${saved ? ' saved' : ''}`}
      onClick={toggle}
      aria-pressed={saved}
      aria-label={saved ? 'Remove bookmark' : 'Save bookmark'}
      title={error || (saved ? 'Remove bookmark' : 'Save bookmark')}
      disabled={busy}
    >
      <BookmarkIcon filled={saved} width={19} height={19} />
    </button>
  );
}
