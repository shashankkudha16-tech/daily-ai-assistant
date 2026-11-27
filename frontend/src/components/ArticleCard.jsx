import { Link } from 'react-router-dom';
import BookmarkToggle from './BookmarkToggle.jsx';
import { formatDate, hostOf } from '../utils/format.js';

export default function ArticleCard({ article, compact = false, showDescription = true, onBookmarkChange }) {
  return (
    <article className={`card article-card${compact ? ' compact' : ''}`}>
      <div className="article-card-top">
        <span className="pill">{article.categoryName || 'AI news'}</span>
        {article.region && article.region !== 'UNKNOWN' && <span className="pill muted">{article.region === 'INDIA' ? 'India source' : 'Global source'}</span>}
        <BookmarkToggle
          itemType="ARTICLE"
          itemId={article.id}
          initial={article.bookmarked}
          onChange={onBookmarkChange}
        />
      </div>

      <Link to={`/news/${article.id}`} className="card-link">
        <h3>{article.title}</h3>
        {showDescription && article.description && (
          <p className="card-description">{article.description}</p>
        )}
      </Link>

      {article.usefulness && (
        <div className="usefulness-preview">
          <span className={`badge recommendation ${article.usefulness.recommendation.toLowerCase()}`}>
            {recommendationLabel(article.usefulness.recommendation)}
          </span>
          <span className="muted small">{article.usefulness.recommendationReason}</span>
        </div>
      )}

      <div className="card-meta">
        <span>{article.source || hostOf(article.url)}</span>
        <span aria-hidden="true">·</span>
        <span>{article.author || 'Author unavailable'}</span>
        <span aria-hidden="true">·</span>
        <time dateTime={article.publishedAt || undefined}>{formatDate(article.publishedAt)}</time>
        <Link className="read-more" to={`/news/${article.id}`} aria-label={`Read ${article.title}`}>
          Read more
        </Link>
      </div>
    </article>
  );
}

function recommendationLabel(value) {
  return ({ LEARN_NOW: 'Learn now', EXPLORE_LATER: 'Explore later', SKIP: 'Skip' })[value] || 'Explore later';
}
