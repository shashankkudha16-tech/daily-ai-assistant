import { Link } from 'react-router-dom';
import useAsync from '../hooks/useAsync.js';
import { EmptyState, ErrorState, SkeletonList } from '../components/States.jsx';
import { getDailyPlan, getHistory, getPersonalPlanHistory, getProgressEntries } from '../api/api.js';
import { formatDateTime } from '../utils/format.js';

async function loadHistory() {
  const [reading, journal, plan, personalPlan] = await Promise.all([getHistory(20), getProgressEntries(), getDailyPlan(), getPersonalPlanHistory()]);
  const tasks = (plan?.days || []).flatMap((day) => day.tasks || []).filter((task) => task.completedInPlan);
  return { reading, journal, tasks, personalPlan };
}

export default function HistoryPage() {
  const { data, loading, error, reload } = useAsync(loadHistory, []);
  if (loading) return <SkeletonList rows={4} />;
  if (error) return <ErrorState message={error} onRetry={reload} />;

  const hasHistory = data.reading.length || data.journal.length || data.tasks.length || data.personalPlan.length;
  return (
    <div className="stack">
      <header className="page-title"><div><p className="eyebrow">Your record</p><h1>History</h1><p className="muted">Journal entries, learning tasks, and articles you opened.</p></div></header>
      {!hasHistory && <EmptyState title="Your history starts here" message="Save a daily entry, finish a learning task, or open an article to build a record." />}
      {!!data.journal.length && <section className="stack tight">
        <div className="section-header"><div><h2>Daily progress</h2><p className="muted">{data.journal.length} saved dates</p></div></div>
        {data.journal.map((entry) => <Link className="card history-card" to="/progress" key={entry.id}>
          <h3>{entry.date}</h3><p>{entry.learned || entry.practiced || entry.built || 'Progress entry saved'}</p>
          <span className="muted small">Updated {formatDateTime(entry.updatedAt)}</span>
        </Link>)}
      </section>}
      {!!data.tasks.length && <section className="stack tight">
        <div className="section-header"><div><h2>Completed learning tasks</h2></div></div>
        {data.tasks.map((task) => <article className="card" key={task.id}><h3>{task.title}</h3>{task.description && <p className="muted">{task.description}</p>}</article>)}
      </section>}
      {!!data.personalPlan.length && <section className="stack tight">
        <div className="section-header"><div><h2>Personal plan history</h2><p className="muted">Evidence-linked actions created from your saved learning records and interests.</p></div></div>
        {data.personalPlan.map((item) => <article className="card history-card" key={item.id}>
          <span className="pill">{item.section.replace('_', ' ')}</span><h3>{item.title}</h3>
          <p>{item.rationale}</p><span className="muted small">{item.planDate} · {item.status.toLowerCase()} · {item.estimatedMinutes} min</span>
        </article>)}
      </section>}
      {!!data.reading.length && <section className="stack tight">
        <div className="section-header"><div><h2>Reading history</h2></div></div>
        {data.reading.map((item) => <Link className="card history-card" to={`/news/${item.articleId}`} key={item.id}>
          <h3>{item.title}</h3><p className="muted small">{item.source} · opened {formatDateTime(item.readAt)}</p>
        </Link>)}
      </section>}
    </div>
  );
}
