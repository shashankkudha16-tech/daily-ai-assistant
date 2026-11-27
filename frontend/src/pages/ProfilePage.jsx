import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getProfile, updateProfileCareerContext, updateProfileName, updateProfilePreferences } from '../api/api.js';
import useAsync from '../hooks/useAsync.js';
import { ErrorState, SkeletonList } from '../components/States.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

export default function ProfilePage() {
  const { signOut } = useAuth();
  const { data: profile, loading, error, reload, setData } = useAsync(getProfile, []);
  const [name, setName] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [saveError, setSaveError] = useState('');
  const [preferences, setPreferences] = useState([]);
  const [preferencesBusy, setPreferencesBusy] = useState(false);
  const [preferencesMessage, setPreferencesMessage] = useState('');
  const [preferencesError, setPreferencesError] = useState('');
  const [careerGoal, setCareerGoal] = useState('');
  const [targetRoles, setTargetRoles] = useState('');
  const [careerBusy, setCareerBusy] = useState(false);
  const [careerMessage, setCareerMessage] = useState('');
  const [careerError, setCareerError] = useState('');

  const interestOptions = [
    ['AI_ML', 'AI and Machine Learning'],
    ['GENERATIVE_AI', 'Generative AI'],
    ['AI_CODING_AGENTS', 'AI Coding Agents'],
    ['JAVA_BACKEND', 'Java and Backend Development'],
    ['DEVELOPER_TOOLS', 'Developer Tools'],
    ['AI_AUTOMATION', 'AI Automation'],
    ['CLOUD_AI_INFRASTRUCTURE', 'Cloud and AI Infrastructure'],
    ['AI_RESEARCH', 'AI Research'],
  ];

  useEffect(() => {
    if (profile?.name) setName(profile.name);
  }, [profile?.name]);

  useEffect(() => {
    setPreferences(profile?.preferences || []);
  }, [profile?.preferences]);

  useEffect(() => {
    setCareerGoal(profile?.careerGoal || '');
    setTargetRoles(profile?.targetRoles || '');
  }, [profile?.careerGoal, profile?.targetRoles]);

  async function saveName(event) {
    event.preventDefault();
    if (!name.trim() || saving) return;
    setSaving(true);
    setMessage('');
    setSaveError('');
    try {
      const updated = await updateProfileName(name.trim());
      setData(updated);
      setMessage('Profile updated.');
    } catch (err) {
      setSaveError(err?.response?.data?.message || 'Could not update your name.');
    } finally {
      setSaving(false);
    }
  }

  async function savePreferences(next = preferences) {
    setPreferencesBusy(true);
    setPreferencesMessage('');
    setPreferencesError('');
    try {
      const updated = await updateProfilePreferences(next);
      setData(updated);
      setPreferencesMessage(next.length ? 'Interests saved. Update recommendations will use these choices.' : 'Interests reset. Recommendations are neutral again.');
    } catch (err) {
      setPreferencesError(err?.response?.data?.message || 'Could not save interests.');
    } finally {
      setPreferencesBusy(false);
    }
  }

  async function saveCareer(event) {
    event.preventDefault();
    setCareerBusy(true);
    setCareerMessage('');
    setCareerError('');
    try {
      const updated = await updateProfileCareerContext(careerGoal, targetRoles);
      setData(updated);
      setCareerMessage('Career context saved for future mentor guidance.');
    } catch (err) {
      setCareerError(err?.response?.data?.message || 'Could not save career context.');
    } finally {
      setCareerBusy(false);
    }
  }

  if (loading) return <SkeletonList rows={4} />;
  if (error) return <ErrorState message={error} onRetry={reload} />;

  return (
    <div className="stack">
      <div className="page-title">
        <div>
          <h1>Settings</h1>
          <p className="muted">Your profile and learning preferences.</p>
        </div>
        <button type="button" className="button ghost small" onClick={signOut}>Log out</button>
      </div>

      <section className="card profile-card">
        <div className="avatar" aria-hidden="true">{profile.name?.slice(0, 1).toUpperCase()}</div>
        <div>
          <h2>{profile.name}</h2>
          <p className="muted small">{profile.email}</p>
        </div>
      </section>

      <section className="stat-grid">
        <div className="stat card">
          <strong>{profile.streakDays}</strong>
          <span>day streak</span>
        </div>
        <div className="stat card">
          <strong>{profile.articlesRead}</strong>
          <span>articles read</span>
        </div>
        <div className="stat card">
          <strong>{profile.completedTopics}/{profile.totalTopics}</strong>
          <span>topics done</span>
        </div>
        <div className="stat card">
          <strong>{profile.bookmarks}</strong>
          <span>bookmarks</span>
        </div>
      </section>

      <section className="card">
        <h2>Display name</h2>
        <form className="inline-form" onSubmit={saveName}>
          <label className="field">
            <span>Name</span>
            <input value={name} onChange={(event) => setName(event.target.value)} maxLength={120} required />
          </label>
          <button type="submit" className="button" disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </form>
        {message && <p className="notice success">{message}</p>}
        {saveError && <p className="notice error">{saveError}</p>}
      </section>

      <section className="card preferences-card">
        <h2>Topics you care about</h2>
        <p className="muted">Optional. These choices help rank updates; they do not change the source facts.</p>
        <div className="preference-grid">
          {interestOptions.map(([value, label]) => (
            <label className="preference-option" key={value}>
              <input
                type="checkbox"
                checked={preferences.includes(value)}
                onChange={(event) => setPreferences((current) => event.target.checked
                  ? [...current, value]
                  : current.filter((item) => item !== value))}
              />
              <span>{label}</span>
            </label>
          ))}
        </div>
        <div className="button-row">
          <button type="button" className="button" disabled={preferencesBusy} onClick={() => savePreferences()}>
            {preferencesBusy ? 'Saving…' : 'Save interests'}
          </button>
          <button type="button" className="button ghost" disabled={preferencesBusy || preferences.length === 0} onClick={() => { setPreferences([]); savePreferences([]); }}>
            Reset to neutral
          </button>
        </div>
        {preferencesMessage && <p className="notice success" role="status">{preferencesMessage}</p>}
        {preferencesError && <p className="notice error" role="alert">{preferencesError}</p>}
      </section>

      <section className="card">
        <h2>Career direction</h2>
        <p className="muted">Optional context helps the mentor connect your learning history to your goals.</p>
        <form className="stack" onSubmit={saveCareer}>
          <label className="field"><span>Career goal</span><textarea rows={3} maxLength={2000} value={careerGoal} onChange={(event) => setCareerGoal(event.target.value)} placeholder="For example, prepare for a backend developer role" /></label>
          <label className="field"><span>Target job roles</span><textarea rows={2} maxLength={2000} value={targetRoles} onChange={(event) => setTargetRoles(event.target.value)} placeholder="List roles you are exploring" /></label>
          <button className="button" type="submit" disabled={careerBusy}>{careerBusy ? 'Saving…' : 'Save career context'}</button>
        </form>
        {careerMessage && <p className="notice success" role="status">{careerMessage}</p>}
        {careerError && <p className="notice error" role="alert">{careerError}</p>}
      </section>

      <section className="card">
        <h2>Saved items</h2>
        <p className="muted">Open articles and learning resources you bookmarked.</p>
        <Link className="button ghost" to="/bookmarks">View bookmarks</Link>
      </section>

    </div>
  );
}
