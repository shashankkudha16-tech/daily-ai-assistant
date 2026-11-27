import { useEffect, useState } from 'react';
import { EmptyState, ErrorState, SkeletonList } from '../components/States.jsx';
import {
  getProgressEntries, getProgressEntry, removeProgressAttachment, saveProgressEntry,
  uploadProgressAttachment,
} from '../api/api.js';
import { formatDateTime } from '../utils/format.js';

const fields = [
  ['learned', 'What did I learn today?'],
  ['practiced', 'What did I practice?'],
  ['built', 'What did I build or work on?'],
  ['difficulties', 'What difficulties did I face?'],
  ['tomorrowGoals', 'What are tomorrow’s goals?'],
];

function localDateValue(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function emptyEntry() {
  return Object.fromEntries(fields.map(([key]) => [key, '']));
}

export default function ProgressPage() {
  const [date, setDate] = useState(localDateValue());
  const [entry, setEntry] = useState(emptyEntry);
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState('');

  async function load(selectedDate = date) {
    setLoading(true);
    setError('');
    try {
      const [selected, entries] = await Promise.all([getProgressEntry(selectedDate), getProgressEntries()]);
      setEntry(selected ? { ...emptyEntry(), ...selected } : emptyEntry());
      setHistory(entries || []);
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not load your progress journal.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(date); }, [date]);

  async function save(event) {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    setMessage('');
    setError('');
    try {
      const saved = await saveProgressEntry(date, Object.fromEntries(fields.map(([key]) => [key, entry[key] || ''])));
      setEntry((current) => ({ ...current, ...saved }));
      setMessage('Progress saved for this date.');
      setHistory((current) => [saved, ...current.filter((item) => item.date !== saved.date)]
        .sort((a, b) => b.date.localeCompare(a.date)));
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not save your progress.');
    } finally {
      setSaving(false);
    }
  }

  async function upload(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || uploading) return;
    setUploading(true);
    setError('');
    setMessage('');
    try {
      const saved = await uploadProgressAttachment(date, file);
      setEntry((current) => ({ ...current, ...saved }));
      setMessage('Document text was extracted and saved with this entry. The original file is not retained.');
      setHistory((current) => [saved, ...current.filter((item) => item.date !== saved.date)]
        .sort((a, b) => b.date.localeCompare(a.date)));
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not read this file. Use a valid PDF, DOCX or UTF-8 TXT file up to 5 MB.');
    } finally {
      setUploading(false);
    }
  }

  async function removeAttachment() {
    try {
      await removeProgressAttachment(date);
      setEntry((current) => ({ ...current, attachmentName: null, attachmentType: null, attachmentText: null }));
      setMessage('Extracted attachment text removed.');
      await load(date);
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not remove the attachment text.');
    }
  }

  if (loading && !history.length) return <SkeletonList rows={4} />;
  if (error && !history.length && !entry.date) return <ErrorState message={error} onRetry={() => load(date)} />;

  return (
    <div className="stack">
      <header className="page-title">
        <div><p className="eyebrow">My Progress</p><h1>Daily journal</h1><p className="muted">Keep a private record of what you learn, practice, build, and want to do next.</p></div>
      </header>
      {error && <p className="notice error" role="alert">{error}</p>}
      <section className="card">
        <form className="stack" onSubmit={save}>
          <label className="field"><span>Journal date</span><input type="date" value={date} max={localDateValue()} onChange={(event) => setDate(event.target.value)} required /></label>
          {fields.map(([key, label]) => (
            <label className="field" key={key}>
              <span>{label}</span>
              <textarea rows={3} maxLength={12000} value={entry[key] || ''} onChange={(event) => setEntry((current) => ({ ...current, [key]: event.target.value }))} />
            </label>
          ))}
          <div className="button-row">
            <button className="button" type="submit" disabled={saving || loading}>{saving ? 'Saving…' : 'Save this day'}</button>
            <label className="button ghost upload-button">
              {uploading ? 'Reading file…' : 'Add PDF, DOCX or TXT'}
              <input type="file" accept=".pdf,.docx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain" onChange={upload} disabled={uploading} />
            </label>
          </div>
          <p className="muted small">Uploads are limited to 5 MB. Text is extracted for your journal; original files are not stored.</p>
          {entry.attachmentName && (
            <section className="notice">
              <strong>{entry.attachmentName}</strong> · {entry.attachmentType}
              <pre className="attachment-preview">{entry.attachmentText}</pre>
              <button type="button" className="text-button" onClick={removeAttachment}>Remove extracted text</button>
            </section>
          )}
          {message && <p className="notice success" role="status">{message}</p>}
        </form>
      </section>
      <section className="stack tight">
        <div className="section-header"><div><h2>Previous entries</h2><p className="muted">Your latest 100 saved journal dates</p></div></div>
        {!history.length ? <EmptyState title="No journal entries yet" message="Save a note about today to start building your growth history." /> : history.map((item) => (
          <button className={`card journal-history-row${item.date === date ? ' selected' : ''}`} type="button" key={item.id || item.date} onClick={() => setDate(item.date)}>
            <strong>{item.date}</strong><span className="muted">{item.learned || item.practiced || item.built || 'Journal entry'}</span>
            <small className="muted">Updated {formatDateTime(item.updatedAt)}</small>
          </button>
        ))}
      </section>
    </div>
  );
}
