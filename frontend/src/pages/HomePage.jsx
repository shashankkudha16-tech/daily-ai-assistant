import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { completeDailyTask, getDashboard, getLatestNews, getGuestRecommendations, resetGuestPersonalization, establishGuestSession } from '../api/api.js';
import useAsync from '../hooks/useAsync.js';
import ArticleCard from '../components/ArticleCard.jsx';
import ToolCard from '../components/ToolCard.jsx';
import { SkeletonList, ErrorState, SectionHeader, EmptyState } from '../components/States.jsx';
import { CheckIcon, ChevronIcon } from '../components/Icons.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { useCallback } from 'react';

export default function HomePage() {
  const { user } = useAuth();
  const loadHome = useCallback(() => user
    ? getDashboard()
    : establishGuestSession().then(() => Promise.all([getLatestNews(12), getGuestRecommendations(12)])).then(([latestNews, recommendations]) => ({
      guest: true, latestNews, recommendations, topUpdates: latestNews.slice(0, 5), trendingTools: [], recentModels: [],
    })), [user]);
  const { data, loading, error, reload, setData } = useAsync(loadHome, [loadHome]);
  const [taskBusy, setTaskBusy] = useState(false);
  const navigate = useNavigate();
  const [resetting, setResetting] = useState(false);
  const [resetError, setResetError] = useState('');

  async function toggleTodayTask(task) {
    if (!task || taskBusy) return;
    setTaskBusy(true);
    try {
      await completeDailyTask(task.id, !task.completedInPlan);
      reload();
    } finally {
      setTaskBusy(false);
    }
  }

  if (loading) {
    return (
      <div className="stack">
        <div className="skeleton-hero" />
        <SkeletonList rows={4} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error} onRetry={reload} />;
  }

  if (!data) {
    return <EmptyState title="No dashboard data" message="Try refreshing the page." />;
  }

  if (data.guest) {
    async function resetInterests() {
      setResetting(true);
      setResetError('');
      try { await resetGuestPersonalization(); reload(); }
      catch { setResetError('Could not reset recommendations. Please try again.'); }
      finally { setResetting(false); }
    }
    return (
      <div className="stack">
        <section className="hero">
          <p className="eyebrow">Daily AI news</p>
          <h1>Latest AI updates</h1>
          <p className="hero-briefing">Browse recent stories from our configured news publishers. Create an account to save articles and track learning progress.</p>
          <Link className="button" to="/discover">Discover all news</Link>
        </section>
        <section>
          <SectionHeader title="Picked for you" subtitle={data.recommendations.message} />
          {data.recommendations.personalized && <p className="muted small">Recommendations use topics from articles you open, spend time reading, and searches. Recent stories are also included for discovery.</p>}
          <div className="stack tight">
            {data.recommendations.articles.map((article) => <ArticleCard key={article.id} article={article} />)}
          </div>
          <button className="text-button" type="button" disabled={resetting} onClick={resetInterests}>
            {resetting ? 'Resetting…' : 'Reset my recommendations'}
          </button>
          {resetError && <p className="muted small" role="alert">{resetError}</p>}
        </section>
        <section>
          <SectionHeader title="Latest AI news" subtitle="Recent items collected from configured feeds" />
          {data.latestNews.length ? <div className="stack tight">{data.latestNews.map((article) => <ArticleCard key={article.id} article={article} />)}</div>
            : <EmptyState title="No news yet" message="New items appear after the next feed refresh." />}
        </section>
      </div>
    );
  }

  const { stats, topUpdates, latestNews, trendingTools, recentModels, todayTask, continueLearning } = data;

  return (
    <div className="stack">
      <section className="hero">
        <div className="hero-top">
          <div>
            <p className="eyebrow">{data.todayLabel}</p>
            <h1>
              {data.greeting}, {data.userName}
            </h1>
          </div>
          <div className="streak" title="Consecutive days with a completed learning item">
            <strong>{data.streakDays}</strong>
            <span>day streak</span>
          </div>
        </div>
        <p className="hero-briefing">{data.briefing}</p>
        <div className="stat-row">
          <div className="stat">
            <strong>{stats.articlesToday}</strong>
            <span>collected today</span>
          </div>
          <div className="stat">
            <strong>{stats.topicProgressPercent}%</strong>
            <span>roadmap done</span>
          </div>
          <div className="stat">
            <strong>{stats.planProgressPercent}%</strong>
            <span>daily plan</span>
          </div>
          <div className="stat">
            <strong>{stats.bookmarks}</strong>
            <span>saved</span>
          </div>
        </div>
      </section>

      {todayTask && (
        <section className="card today-task">
          <div className="article-card-top">
            <span className="pill">Today&apos;s learning</span>
            <span className="muted small">Day {todayTask.dayNumber} · {todayTask.estimatedMinutes} min</span>
          </div>
          <h3>{todayTask.title}</h3>
          {todayTask.description && <p className="card-description">{todayTask.description}</p>}
          <div className="task-footer">
            <span className={`status-dot ${todayTask.completedInPlan ? 'done' : ''}`}>
              {todayTask.completedInPlan ? 'Completed' : todayTask.kind.replace('_', ' ').toLowerCase()}
            </span>
            <button
              type="button"
              className={`button small ${todayTask.completedInPlan ? 'ghost' : ''}`}
              onClick={() => toggleTodayTask(todayTask)}
              disabled={taskBusy}
            >
              {todayTask.completedInPlan ? 'Mark as pending' : 'Mark completed'}
            </button>
          </div>
        </section>
      )}

      {continueLearning && (
        <section className="card continue-card">
          <span className="pill">Continue learning</span>
          <h3>{continueLearning.title}</h3>
          <p className="muted small">
            Level {continueLearning.level} · {continueLearning.levelName} · {continueLearning.status.replace('_', ' ').toLowerCase()}
          </p>
          <button
            type="button"
            className="text-button with-chevron"
            onClick={() => navigate(`/learn/${continueLearning.id}`)}
          >
            Open topic <ChevronIcon width={16} height={16} />
          </button>
        </section>
      )}

      <section>
        <SectionHeader
          title="Top 5 AI updates"
          subtitle="The most recent items collected from the configured feeds"
          action={
            <Link className="text-link" to="/discover">
              See all
            </Link>
          }
        />
        {topUpdates?.length ? (
          <div className="stack tight">
            {topUpdates.map((article) => (
              <ArticleCard key={article.id} article={article} compact />
            ))}
          </div>
        ) : (
          <EmptyState
            title="No updates collected yet"
            message="RSS feeds may be unreachable. New items appear after the next scheduled fetch."
          />
        )}
      </section>

      <section>
        <SectionHeader title="Trending AI tools" subtitle="Verified basics, links and free alternatives" />
        {trendingTools?.length ? (
          <div className="h-scroll">
            {trendingTools.map((tool) => (
              <div className="h-scroll-item" key={tool.id}>
                <ToolCard tool={tool} />
              </div>
            ))}
          </div>
        ) : (
          <EmptyState title="No tools available" />
        )}
      </section>

      {recentModels?.length > 0 && (
        <section>
          <SectionHeader title="Recently released AI models" subtitle="Model releases and related coverage" />
          <div className="stack tight">
            {recentModels.map((article) => (
              <ArticleCard key={article.id} article={article} compact showDescription={false} />
            ))}
          </div>
        </section>
      )}

      <section>
        <SectionHeader title="Latest AI news" subtitle="Actual publication dates from each source" />
        {latestNews?.length ? (
          <div className="stack tight">
            {latestNews.map((article) => (
              <ArticleCard key={article.id} article={article} onBookmarkChange={() => setData({ ...data })} />
            ))}
          </div>
        ) : (
          <EmptyState title="No news yet" message="Use Discover to search after the first refresh." />
        )}
      </section>
    </div>
  );
}
