import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { generateMentorReview, getMentorHistory, getMentorReview } from '../api/api.js';
import { EmptyState, ErrorState, SkeletonList } from '../components/States.jsx';

const sections = [
  ['completedTopics', 'Completed learning topics'],
  ['skillActivity', 'Progress you recorded'],
  ['challenges', 'Challenges you recorded'],
  ['learningGaps', 'Roadmap topics not started'],
];

function ReviewCard({ review }) {
  if (!review) return null;
  return (
    <div className="stack">
      <section className="notice" role="status">
        <strong>{review.status === 'AI' ? 'AI suggestions' : 'Record-based review'}</strong>
        <p>{review.message}</p>
        {review.generatedAt && <small>Generated {new Date(review.generatedAt).toLocaleString()}</small>}
      </section>
      {(review.careerGoal || review.targetRoles) && <section className="card">
        <h2>Career direction on file</h2>
        {review.careerGoal && <p>{review.careerGoal}</p>}
        {review.targetRoles && <p className="muted">Target roles: {review.targetRoles}</p>}
      </section>}
      {sections.map(([key, title]) => (
        <section className="card" key={key}>
          <h2>{title}</h2>
          {review[key]?.length ? <ul className="mentor-observations">{review[key].map((item, index) => (
            <li key={`${item.date}-${item.label}-${index}`}><strong>{item.label}</strong><p>{item.evidence}</p>{item.date && <small className="muted">{item.date}</small>}</li>
          ))}</ul> : <p className="muted">There is not enough recorded information for this section yet.</p>}
        </section>
      ))}
      <section className="card">
        <h2>Suggested next actions</h2>
        {review.recommendations?.length ? <div className="stack tight">{review.recommendations.map((item, index) => (
          <article className="mentor-recommendation" key={`${item.title}-${index}`}>
            <div className="article-card-top"><strong>{item.title}</strong><span className="pill">{item.priority} · {item.estimatedMinutes} min</span></div>
            <p>{item.action}</p>
            {item.rationale && <p className="muted small">Why: {item.rationale}</p>}
            {item.relatedSkill && <p className="muted small">Related to: {item.relatedSkill}</p>}
            {item.basis?.map((basis, basisIndex) => <blockquote key={`${basis.label}-${basisIndex}`}>{basis.evidence}</blockquote>)}
          </article>
        ))}</div> : <p className="muted">No AI actions were accepted for this review. Add more progress or review your roadmap and try again later.</p>}
      </section>
    </div>
  );
}

export default function MentorPage() {
  const [review, setReview] = useState(null);
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [error, setError] = useState('');

  async function load() {
    setLoading(true);
    setError('');
    try {
      const [current, previous] = await Promise.all([getMentorReview(), getMentorHistory()]);
      setReview(current || null);
      setHistory(previous || []);
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not load your mentor review.');
    } finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  async function generate() {
    if (!acknowledged || busy) return;
    setBusy(true);
    setError('');
    try {
      const next = await generateMentorReview();
      setReview(next);
      setHistory(await getMentorHistory());
    } catch (err) {
      setError(err?.response?.data?.message || 'The mentor review could not be generated. Your saved journal remains unchanged.');
    } finally { setBusy(false); }
  }

  if (loading) return <SkeletonList rows={4} />;
  if (error && !review) return <ErrorState message={error} onRetry={load} />;

  return (
    <div className="stack">
      <header className="page-title"><div><p className="eyebrow">Personal AI Mentor</p><h1>Guidance grounded in your history</h1><p className="muted">This review uses your saved journal, tracked roadmap progress, completed tasks, interests, and career direction. It does not invent skill scores or claim improvement without history.</p></div></header>
      <section className="card stack">
        <label className="preference-option"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} /><span>When enabled, my saved learning context may be sent to Groq to generate optional recommendations.</span></label>
        <button className="button" type="button" disabled={!acknowledged || busy} onClick={generate}>{busy ? 'Reviewing your history…' : review ? 'Refresh mentor review' : 'Generate mentor review'}</button>
        <p className="muted small">The review is cached for today to avoid duplicate provider requests. Your history is limited to recent entries and learning records.</p>
        <Link className="text-link" to="/progress">Add a daily progress entry</Link>
      </section>
      {error && <p className="notice error" role="alert">{error}</p>}
      {review ? <ReviewCard review={review} /> : <EmptyState title="No review generated yet" message="Start with a journal entry or update a roadmap topic, then request a review." />}
      {!!history.length && <section className="card"><h2>Previous mentor reviews</h2><div className="stack tight">{history.map((item) => (
        <article key={item.id || item.generatedAt} className="history-card"><strong>{item.contextTo}</strong><span className="muted">{item.status === 'AI' ? 'AI recommendations included' : 'Record-only review'}</span></article>
      ))}</div></section>}
    </div>
  );
}
