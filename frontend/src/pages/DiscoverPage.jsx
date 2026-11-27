import { useState } from 'react';
import {
  getCategories,
  getNews,
  getToolCategories,
  getTools,
  refreshNews,
  recordGuestSearch,
  searchNews,
} from '../api/api.js';
import useAsync from '../hooks/useAsync.js';
import ArticleCard from '../components/ArticleCard.jsx';
import ToolCard from '../components/ToolCard.jsx';
import { EmptyState, ErrorState, SkeletonList } from '../components/States.jsx';
import { RefreshIcon, SearchIcon } from '../components/Icons.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

export default function DiscoverPage() {
  const { user, loading: authLoading } = useAuth();
  const [tab, setTab] = useState('news');
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('all');
  const [region, setRegion] = useState('all');
  const [period, setPeriod] = useState('all');
  const [sort, setSort] = useState('newest');
  const [page, setPage] = useState(0);

  const [toolQueryInput, setToolQueryInput] = useState('');
  const [toolQuery, setToolQuery] = useState('');
  const [toolCategory, setToolCategory] = useState('all');
  const [toolPage, setToolPage] = useState(0);

  const [refreshing, setRefreshing] = useState(false);
  const [refreshMessage, setRefreshMessage] = useState('');
  const [refreshError, setRefreshError] = useState('');

  const categories = useAsync(getCategories, []);
  const toolCategories = useAsync(getToolCategories, []);

  const news = useAsync(
    () => (query ? searchNews({ q: query, category, region, period, sort, page, size: 10 }) : getNews({ category, region, period, sort, page, size: 10 })),
    [query, category, region, period, sort, page]
  );

  const tools = useAsync(
    () => getTools({ q: toolQuery || undefined, category: toolCategory === 'all' ? undefined : toolCategory, page: toolPage, size: 12 }),
    [toolQuery, toolCategory, toolPage]
  );

  function submitSearch(event) {
    event.preventDefault();
    setPage(0);
    const value = queryInput.trim();
    setQuery(value);
    if (!authLoading && !user && value) recordGuestSearch(value).catch(() => {});
  }

  function submitToolSearch(event) {
    event.preventDefault();
    setToolPage(0);
    setToolQuery(toolQueryInput.trim());
  }

  async function handleRefresh() {
    setRefreshing(true);
    setRefreshMessage('');
    setRefreshError('');
    try {
      const result = await refreshNews();
      setRefreshMessage(
        `Checked ${result.feedsAttempted} feeds: ${result.saved} new articles, ${result.duplicates} duplicates, ${result.feedsFailed} failed.`
      );
      news.reload();
      categories.reload();
    } catch (error) {
      setRefreshError(
        error?.response?.status === 403
          ? 'Admin access is required to refresh feeds. Use the scheduler or an admin account.'
          : 'Could not refresh the news feeds right now.'
      );
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <div className="stack">
      <div className="page-title">
        <h1>Discover</h1>
        {user?.role === 'ADMIN' && (
          <button type="button" className="icon-button" onClick={handleRefresh} disabled={refreshing} title="Fetch feeds now" aria-label="Refresh news feeds">
            <RefreshIcon className={refreshing ? 'spin' : ''} />
          </button>
        )}
      </div>

      {refreshMessage && <p className="notice success">{refreshMessage}</p>}
      {refreshError && <p className="notice error">{refreshError}</p>}

      <div className="segmented" role="tablist" aria-label="Discover type">
        <button type="button" role="tab" aria-selected={tab === 'news'} className={tab === 'news' ? 'active' : ''} onClick={() => setTab('news')}>
          AI news
        </button>
        <button type="button" role="tab" aria-selected={tab === 'tools'} className={tab === 'tools' ? 'active' : ''} onClick={() => setTab('tools')}>
          AI tools
        </button>
      </div>

      {tab === 'news' ? (
        <>
          <form className="search-bar" onSubmit={submitSearch} role="search">
            <SearchIcon width={18} height={18} />
            <input
              type="search"
              placeholder="Search titles, descriptions or sources"
              value={queryInput}
              onChange={(event) => setQueryInput(event.target.value)}
              aria-label="Search AI news"
            />
            <button type="submit" className="button small">Search</button>
          </form>

          <div className="chip-row" role="group" aria-label="Filter by category">
            <button type="button" className={`chip${category === 'all' ? ' active' : ''}`} onClick={() => { setCategory('all'); setPage(0); }}>
              All
            </button>
            {categories.data?.map((item) => (
              <button
                key={item.slug}
                type="button"
                className={`chip${category === item.slug ? ' active' : ''}`}
                onClick={() => { setCategory(item.slug); setPage(0); }}
              >
                {item.name} <span className="chip-count">{item.articleCount}</span>
              </button>
            ))}
          </div>

          <div className="filter-row">
            <span className="muted small">{query ? `Results for “${query}”` : 'All collected articles'}</span>
            <label className="select-label">
              Source region
              <select value={region} onChange={(event) => { setRegion(event.target.value); setPage(0); }}>
                <option value="all">India + global</option>
                <option value="INDIA">India source</option>
                <option value="GLOBAL">Global source</option>
              </select>
            </label>
            <label className="select-label">
              Published
              <select value={period} onChange={(event) => { setPeriod(event.target.value); setPage(0); }}>
                <option value="all">Any time</option>
                <option value="today">Today</option>
                <option value="week">This week</option>
              </select>
            </label>
            <label className="select-label">
              Sort
              <select value={sort} onChange={(event) => { setSort(event.target.value); setPage(0); }}>
                <option value="newest">Newest first</option>
                <option value="oldest">Oldest first</option>
                <option value="title">Title A–Z</option>
              </select>
            </label>
          </div>

          {news.loading && <SkeletonList rows={4} />}
          {news.error && <ErrorState message={news.error} onRetry={news.reload} />}
          {!news.loading && !news.error && news.data?.content?.length === 0 && (
            <EmptyState
              title="No matching articles"
              message={query ? 'Try a different keyword or category.' : 'No articles yet. Run a refresh to fetch feeds.'}
            />
          )}
          {!news.loading && !news.error && news.data?.content?.length > 0 && (
            <>
              <div className="stack tight">
                {news.data.content.map((article) => (
                  <ArticleCard key={article.id} article={article} />
                ))}
              </div>
              <Pager page={news.data.page} totalPages={news.data.totalPages} total={news.data.totalElements} onChange={setPage} />
            </>
          )}
        </>
      ) : (
        <>
          <form className="search-bar" onSubmit={submitToolSearch} role="search">
            <SearchIcon width={18} height={18} />
            <input
              type="search"
              placeholder="Search AI tools"
              value={toolQueryInput}
              onChange={(event) => setToolQueryInput(event.target.value)}
              aria-label="Search AI tools"
            />
            <button type="submit" className="button small">Search</button>
          </form>

          <div className="chip-row" role="group" aria-label="Filter by tool category">
            <button type="button" className={`chip${toolCategory === 'all' ? ' active' : ''}`} onClick={() => { setToolCategory('all'); setToolPage(0); }}>
              All
            </button>
            {toolCategories.data?.map((item) => (
              <button
                key={item}
                type="button"
                className={`chip${toolCategory === item ? ' active' : ''}`}
                onClick={() => { setToolCategory(item); setToolPage(0); }}
              >
                {item}
              </button>
            ))}
          </div>

          {tools.loading && <SkeletonList rows={3} />}
          {tools.error && <ErrorState message={tools.error} onRetry={tools.reload} />}
          {!tools.loading && !tools.error && tools.data?.content?.length === 0 && (
            <EmptyState title="No tools found" message="Try another category or search term." />
          )}
          {!tools.loading && !tools.error && tools.data?.content?.length > 0 && (
            <>
              <div className="stack tight">
                {tools.data.content.map((tool) => (
                  <ToolCard key={tool.id} tool={tool} />
                ))}
              </div>
              <Pager page={tools.data.page} totalPages={tools.data.totalPages} total={tools.data.totalElements} onChange={setToolPage} />
            </>
          )}
        </>
      )}
    </div>
  );
}

function Pager({ page, totalPages, total, onChange }) {
  if (totalPages <= 1) {
    return <p className="muted small centered">{total} item{total === 1 ? '' : 's'}</p>;
  }
  return (
    <div className="pager">
      <button type="button" className="button ghost small" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      <span className="muted small">Page {page + 1} of {totalPages}</span>
      <button type="button" className="button ghost small" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </button>
    </div>
  );
}
