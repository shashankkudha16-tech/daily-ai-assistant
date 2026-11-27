import { useState } from 'react';
import { Link } from 'react-router-dom';
import { completeDailyTask, createOrLoadPersonalPlanToday, getDailyPlan, getRoadmap, updatePersonalPlanStatus } from '../api/api.js';
import useAsync from '../hooks/useAsync.js';
import { EmptyState, ErrorState, SectionHeader, SkeletonList } from '../components/States.jsx';
import { CheckIcon, ChevronIcon } from '../components/Icons.jsx';
import { STATUS_LABELS } from '../utils/format.js';

export default function LearnPage() {
  const roadmap = useAsync(getRoadmap, []);
  const plan = useAsync(getDailyPlan, []);
  const personalPlan = useAsync(createOrLoadPersonalPlanToday, []);
  const [openLevels, setOpenLevels] = useState({ 1: true });
  const [busyTask, setBusyTask] = useState(null);
  const [busyPersonalItem, setBusyPersonalItem] = useState(null);
  const [personalPlanActionError, setPersonalPlanActionError] = useState('');

  function toggleLevel(level) {
    setOpenLevels((current) => ({ ...current, [level]: !current[level] }));
  }

  async function toggleTask(task) {
    if (busyTask) return;
    setBusyTask(task.id);
    try {
      await completeDailyTask(task.id, !task.completedInPlan);
      plan.reload();
    } finally {
      setBusyTask(null);
    }
  }

  async function togglePersonalItem(item) {
    if (busyPersonalItem) return;
    setBusyPersonalItem(item.id);
    setPersonalPlanActionError('');
    try {
      await updatePersonalPlanStatus(item.id, item.status === 'COMPLETED' ? 'PENDING' : 'COMPLETED');
      personalPlan.reload();
    } catch {
      setPersonalPlanActionError('Could not update this learning-plan task. Please try again.');
    } finally { setBusyPersonalItem(null); }
  }

  return (
    <div className="stack">
      <div className="page-title">
        <div>
          <h1>Learn</h1>
          <p className="muted">A five-level roadmap from fundamentals to shipping AI features.</p>
        </div>
      </div>

      <section className="card progress-card">
        <div className="progress-heading">
          <span>Roadmap progress</span>
          <strong>{roadmap.data?.progressPercent ?? 0}%</strong>
        </div>
        <div className="progress-track">
          <div className="progress-fill" style={{ width: `${roadmap.data?.progressPercent ?? 0}%` }} />
        </div>
        <p className="muted small">
          {roadmap.data ? `${roadmap.data.completedTopics} of ${roadmap.data.totalTopics} topics completed` : 'Loading topics…'}
        </p>
      </section>

      {roadmap.loading && <SkeletonList rows={4} />}
      {roadmap.error && <ErrorState message={roadmap.error} onRetry={roadmap.reload} />}
      {!roadmap.loading && !roadmap.error && !roadmap.data?.levels?.length && (
        <EmptyState title="No learning topics" message="Run the database migrations to seed the roadmap." />
      )}

      {roadmap.data?.levels?.map((level) => {
        const open = Boolean(openLevels[level.level]);
        return (
          <section className="level-card" key={level.level}>
            <button type="button" className="level-heading" onClick={() => toggleLevel(level.level)} aria-expanded={open}>
              <div>
                <span className="eyebrow">Level {level.level}</span>
                <h2>{level.levelName}</h2>
                <p className="muted small">{level.completedTopics} of {level.totalTopics} completed</p>
              </div>
              <ChevronIcon className={`chevron${open ? ' open' : ''}`} />
            </button>

            {open && (
              <div className="topic-list">
                {level.topics.map((topic) => (
                  <Link to={`/learn/${topic.id}`} className="topic-row" key={topic.id}>
                    <span className={`topic-status ${topic.status.toLowerCase()}`}>
                      {topic.status === 'COMPLETED' ? <CheckIcon width={14} height={14} /> : null}
                    </span>
                    <span className="topic-row-text">
                      <strong>{topic.title}</strong>
                      <span className="muted small">{STATUS_LABELS[topic.status] || topic.status}</span>
                    </span>
                    <ChevronIcon width={17} height={17} />
                  </Link>
                ))}
              </div>
            )}
          </section>
        );
      })}

      <section className="stack">
        <SectionHeader title="Your plan for today" subtitle="Built from your roadmap, journal difficulties and recent articles in your saved interests" />
        {personalPlan.loading && <SkeletonList rows={2} />}
        {personalPlan.error && <ErrorState message={personalPlan.error} onRetry={personalPlan.reload} />}
        {personalPlanActionError && <p className="notice error" role="alert">{personalPlanActionError}</p>}
        {!personalPlan.loading && !personalPlan.error && !personalPlan.data?.length && (
          <EmptyState title="No grounded action to suggest yet" message="Update roadmap progress, record a difficulty, or choose news interests in Settings to build a plan from your own data." />
        )}
        {!!personalPlan.data?.length && ['LEARN', 'PRACTICE', 'STAY_UPDATED'].map((section) => {
          const items = personalPlan.data.filter((item) => item.section === section);
          if (!items.length) return null;
          return <section className="stack tight" key={section}>
            <h3>{({ LEARN: 'Learn', PRACTICE: 'Practice', STAY_UPDATED: 'Stay updated' })[section]}</h3>
            {items.map((item) => <article className="card stack tight" key={item.id}>
              <div className="article-card-top"><span className="pill">{item.generationSource === 'AI' ? 'AI suggestion' : 'Record-based suggestion'}</span><span className="pill">{item.priority} priority</span><span className="muted small">{item.estimatedMinutes} min · due {item.dueDate}</span></div>
              <h3>{item.title}</h3><p>{item.description}</p><p className="muted small">Why this: {item.rationale}</p>
              {item.relatedSkill && <p className="muted small">Related skill: {item.relatedSkill}</p>}
              {item.sourceArticleUrl && <a className="text-link" href={item.sourceArticleUrl} target="_blank" rel="noreferrer">Open the original article</a>}
              <button type="button" className="button small" disabled={busyPersonalItem === item.id} onClick={() => togglePersonalItem(item)}>
                {item.status === 'COMPLETED' ? 'Mark pending' : 'Mark complete'}
              </button>
            </article>)}
          </section>;
        })}
      </section>

      <section>
        <SectionHeader title="Daily learning plan" subtitle="Seven days of short, practical tasks" />
        {plan.loading && <SkeletonList rows={2} />}
        {plan.error && <ErrorState message={plan.error} onRetry={plan.reload} />}
        {plan.data && (
          <>
            <div className="card progress-card">
              <div className="progress-heading">
                <span>Plan progress</span>
                <strong>{plan.data.progressPercent}%</strong>
              </div>
              <div className="progress-track">
                <div className="progress-fill" style={{ width: `${plan.data.progressPercent}%` }} />
              </div>
              <p className="muted small">
                {plan.data.completedTasks} of {plan.data.totalTasks} tasks completed
              </p>
            </div>

            <div className="stack tight">
              {plan.data.days.map((day) => (
                <div className="card plan-day" key={day.dayNumber}>
                  <div className="plan-day-heading">
                    <h3>Day {day.dayNumber}</h3>
                    <span className="muted small">{day.completedTasks}/{day.totalTasks} done</span>
                  </div>
                  <ul className="task-list">
                    {day.tasks.map((task) => (
                      <li key={task.id}>
                        <button
                          type="button"
                          className={`task-toggle${task.completedInPlan ? ' done' : ''}`}
                          onClick={() => toggleTask(task)}
                          disabled={busyTask === task.id}
                          aria-pressed={task.completedInPlan}
                        >
                          <span className="checkbox">{task.completedInPlan && <CheckIcon width={13} height={13} />}</span>
                          <span>
                            <strong>{task.title}</strong>
                            {task.description && <span className="task-description">{task.description}</span>}
                            <span className="muted small">{task.kind.toLowerCase()} · {task.estimatedMinutes} min</span>
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </>
        )}
      </section>
    </div>
  );
}
