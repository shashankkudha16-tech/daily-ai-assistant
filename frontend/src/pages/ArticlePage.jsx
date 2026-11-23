import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getArticle, recordGuestInteraction, saveRecommendationFeedback, getRecommendationFeedback } from '../api/api.js';
import useAsync from '../hooks/useAsync.js';
import BookmarkToggle from '../components/BookmarkToggle.jsx';
import { ErrorState, EmptyState, SkeletonList } from '../components/States.jsx';
import { ArrowLeftIcon, ExternalIcon } from '../components/Icons.jsx';
import { formatDateTime } from '../utils/format.js';
import { useAuth } from '../auth/AuthContext.jsx';

const SECTIONS = [
  ['whatHappened', 'What happened?'],
  ['importance', 'Why does it matter?'],
  ['whoBenefits', 'Who benefits?'],
  ['useCases', 'How can I use it?'],
  ['technicalExplanation', 'Technical explanation'],
  ['technology', 'Technical term, simply explained'],
  ['howItWorks', 'How does it work?'],
  ['whereUsed', 'Where is it used?'],
  ['fresherLearning', 'Optional learning next step'],
  ['practicalExample', 'Practical example from the explanation'],
  ['relatedTechnologies', 'Related technologies'],
];

const CLAIM_KINDS = {
  SOURCE_FACT: 'Source fact',
  INTERPRETATION: 'App interpretation',
};

const sectionLabel = (key) => SECTIONS.find(([section]) => section === key)?.[1] || 'Explanation';

const recommendationLabel = (value) => ({
  LEARN_NOW: 'Learn now',
  EXPLORE_LATER: 'Explore later',
  SKIP: 'Skip',
})[value] || 'Explore later';

const NOT_AVAILABLE = 'Not available in the provided abstract.';

function conciseExcerpt(value, isResearch = false) {
  if (!value?.trim()) return '';
  const text = isResearch ? value.replace(/^.*?\bAbstract:\s*/is, '') : value;
  const sentence = text.trim().split(/(?<=[.!?])\s+(?=[A-Z0-9])/)[0] || text.trim();
  if (sentence.length <= 280) return sentence;
  const excerpt = sentence.slice(0, 277).replace(/\s+\S*$/, '');
  return `${excerpt}…`;
}

export default function ArticlePage() {
  const { id } = useParams();
  const { user, loading: authLoading } = useAuth();
  const { data: article, loading, error, reload } = useAsync(() => getArticle(id), [id]);

  useEffect(() => {
    if (!article?.id || authLoading || user) return undefined;
    recordGuestInteraction(article.id, 'OPEN').catch(() => {});
    const timer = window.setTimeout(() => {
      if (document.visibilityState === 'visible') {
        recordGuestInteraction(article.id, 'QUALIFIED_VIEW').catch(() => {});
      }
    }, 25000);
    return () => window.clearTimeout(timer);
  }, [article?.id, authLoading, user]);

  if (loading) return <SkeletonList rows={5} />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  if (!article) return <EmptyState title="Article not found" />;

  return (
    <div className="stack">
      <Link to="/discover" className="back-link">
        <ArrowLeftIcon width={18} height={18} /> Back to discover
      </Link>

      <article className="stack article-detail">
        <div className="article-card-top">
          <span className="pill">{article.categoryName || 'AI news'}</span>
          <BookmarkToggle itemType="ARTICLE" itemId={article.id} initial={article.bookmarked} />
        </div>

        <h1 className="detail-title">{article.title}</h1>

        <div className="detail-meta">
          <span>{article.source}</span>
          <span aria-hidden="true">·</span>
          <span>{article.author || 'Author unavailable'}</span>
          <span aria-hidden="true">·</span>
          <time dateTime={article.publishedAt || undefined}>{formatDateTime(article.publishedAt)}</time>
        </div>

        <section className="quick-summary" aria-labelledby="quick-summary-title">
          <div className="explanation-heading">
            <h2 id="quick-summary-title">Quick summary</h2>
            <span className="badge">{article.explanation?.generatedBy === 'AI'
              ? 'AI interpretation'
              : article.researchSummary?.beginnerSummary && article.researchSummary.beginnerSummary !== NOT_AVAILABLE
                ? 'Plain-language from abstract'
                : 'Source excerpt'}</span>
          </div>
          <p className="lead">
            {article.researchSummary?.beginnerSummary && article.researchSummary.beginnerSummary !== NOT_AVAILABLE
              ? article.researchSummary.beginnerSummary
              : article.explanation?.generatedBy === 'AI' && article.explanation.whatHappened
                ? article.explanation.whatHappened
              : conciseExcerpt(article.description, Boolean(article.researchSummary)) || 'The feed provides a title but no summary. Open the original source for details.'}
          </p>
          {article.description && (
            <details className="source-summary">
              <summary>Show the publisher’s full summary or abstract</summary>
              <p>{article.description}</p>
            </details>
          )}
        </section>

        {article.researchSummary && (
          <section className="research-summary" aria-labelledby="research-summary-title">
            <h2 id="research-summary-title">Research paper at a glance</h2>
          <p className="muted small">Selected complete sentences from the publisher abstract:</p>
            <ResearchSummaryItem title="Research problem" value={article.researchSummary.researchProblem} />
            <ResearchSummaryItem title="What researchers investigated" value={article.researchSummary.investigation} />
            <ResearchSummaryItem title="Main finding" value={article.researchSummary.mainFinding} />
            <ResearchSummaryItem title="Why it may matter" value={article.researchSummary.whyItMatters} />
            <ResearchSummaryItem title="For a beginner developer" value={article.researchSummary.beginnerDeveloperRelevance} interpretation />
            {article.researchSummary.plainLanguageTerms !== NOT_AVAILABLE && (
              <ResearchSummaryItem title="Technical term, simply explained" value={article.researchSummary.plainLanguageTerms} interpretation />
            )}
            <p className="muted small">{article.researchSummary.evidenceNote}</p>
          </section>
        )}

        {article.usefulness && (
          <section className="usefulness-panel" aria-labelledby="usefulness-title">
            <div className="explanation-heading">
              <h2 id="usefulness-title">Should I learn it?</h2>
              <span className={`badge recommendation ${article.usefulness.recommendation.toLowerCase()}`}>
                {recommendationLabel(article.usefulness.recommendation)}
              </span>
            </div>
            <p>{article.usefulness.recommendationReason}</p>
            <dl className="usefulness-details">
              <div><dt>Who benefits</dt><dd>{article.usefulness.whoBenefits}</dd></div>
              <div><dt>Relevance to you</dt><dd>{article.usefulness.relevanceToInterests}</dd></div>
              <div><dt>How can I use it?</dt><dd>{article.usefulness.practicalUseCase}</dd></div>
              {article.usefulness.estimatedLearningMinutes && (
                <div><dt>Optional learning time</dt><dd>About {article.usefulness.estimatedLearningMinutes} minutes</dd></div>
              )}
            </dl>
          </section>
        )}

        <p className="notice">
          Publisher facts and research excerpts come from the feed text above. Recommendations and plain-language
          notes are app interpretations; the feed may not support every claim. Check the original paper before
          applying technical details.
        </p>

        {article.usefulness && <RecommendationFeedback articleId={article.id} isGuest={!user} />}

        {article.explanation ? (
          <section className="explanation">
            <div className="explanation-heading">
              <h2>Beginner-friendly explanation</h2>
              <span
                className={`badge ${article.explanation.generatedBy === 'AI' ? 'ai' : ''}`}
                title={article.explanation.generatedBy === 'AI'
                  ? `${article.explanation.provider || 'AI provider'} · ${article.explanation.model || 'model'} · ${article.explanation.latencyMs || 0} ms`
                  : 'Built from the publisher text without an AI provider'}
              >
                {article.explanation.generatedBy === 'AI' ? 'AI interpretation' : 'Source-based BASIC explanation'}
              </span>
            </div>
            {SECTIONS.filter(([key]) => key !== 'fresherLearning' || article.usefulness?.recommendation === 'LEARN_NOW').map(([key, label]) => (
              <div className="explanation-item" key={key}>
                <h3>{label}</h3>
                <p>{article.explanation[key] || 'Not available in the provided text.'}</p>
              </div>
            ))}
            {article.explanation.claims?.length > 0 && (
              <div className="explanation-item claim-evidence">
                <h3>Source-grounded claims</h3>
                <p className="muted small">
                  Each statement below is paired with the publisher quote used as evidence. A quote supports
                  only its own claim; it is not proof of every statement on this page.
                </p>
                <ul className="claim-list">
                  {article.explanation.claims.map((claim, index) => (
                    <li key={`${claim.statement}-${index}`}>
                      {(claim.kind || claim.section) && (
                        <div className="claim-meta">
                          {claim.kind && (
                            <span className={`claim-kind ${claim.kind === 'INTERPRETATION' ? 'interpretation' : ''}`}>
                              {CLAIM_KINDS[claim.kind] || claim.kind}
                            </span>
                          )}
                          {claim.section && <span className="claim-section">Relates to: {sectionLabel(claim.section)}</span>}
                        </div>
                      )}
                      <span>{claim.statement}</span>
                      <blockquote>{claim.evidence}</blockquote>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </section>
        ) : (
          <EmptyState title="No explanation yet" message="Open the original article for the full story." />
        )}

        <div className="button-row">
          <a className="button" href={article.url} target="_blank" rel="noreferrer">
            Read the original article <ExternalIcon width={16} height={16} />
          </a>
        </div>
      </article>
    </div>
  );
}

function ResearchSummaryItem({ title, value, interpretation = false }) {
  return (
    <div className={`research-summary-item${interpretation ? ' interpretation' : ''}`}>
      <h3>{title}{interpretation && <span> · interpretation</span>}</h3>
      <p>{value || NOT_AVAILABLE}</p>
    </div>
  );
}

function RecommendationFeedback({ articleId, isGuest }) {
  const key = `recommendation-feedback-${articleId}`;
  const [feedback, setFeedback] = useState(() => isGuest ? '' : window.localStorage.getItem(key) || '');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    if (isGuest) {
      getRecommendationFeedback(articleId).then((action) => {
        if (!active) return;
        const normalized = action.toLowerCase().replaceAll('_', '-');
        setFeedback(normalized);
        if (normalized) window.localStorage.setItem(key, normalized);
        else window.localStorage.removeItem(key);
      }).catch(() => {
        if (active) setStatus('Could not load your saved feedback.');
      });
    }
    return () => { active = false; };
  }, [articleId, isGuest, key]);

  async function choose(value) {
    if (busy) return;
    const next = feedback === value ? '' : value;
    setBusy(true);
    setStatus('');
    try {
      if (isGuest) {
        const action = next ? next.replaceAll('-', '_').toUpperCase() : 'EXPLORE_LATER';
        await saveRecommendationFeedback(articleId, action);
      }
      if (next) window.localStorage.setItem(key, next);
      else window.localStorage.removeItem(key);
      const saved = isGuest ? await getRecommendationFeedback(articleId) : next;
      setFeedback(saved.toLowerCase().replaceAll('_', '-'));
      setStatus(next === 'fewer'
        ? 'Saved. This article is excluded from your future guest recommendations.'
        : next === 'not-useful'
          ? 'Saved. We’ll reduce this topic in your guest recommendations.'
          : next === 'useful'
            ? 'Saved. This does not change topic interests or recommendation selection.'
            : isGuest
              ? 'Saved as Explore later. This does not change topic interests or recommendation selection.'
              : 'Feedback cleared.');
    } catch {
      setStatus('Could not save your feedback. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="recommendation-feedback" aria-label="Recommendation feedback">
      <p>Was this recommendation useful?</p>
      <div className="button-row">
        <button type="button" disabled={busy} className={`button small ${feedback === 'useful' ? '' : 'ghost'}`} aria-pressed={feedback === 'useful'} onClick={() => choose('useful')}>Useful</button>
        <button type="button" disabled={busy} className={`button small ${feedback === 'not-useful' ? '' : 'ghost'}`} aria-pressed={feedback === 'not-useful'} onClick={() => choose('not-useful')}>Not useful</button>
        <button type="button" disabled={busy} className={`button small ${feedback === 'fewer' ? '' : 'ghost'}`} aria-pressed={feedback === 'fewer'} onClick={() => choose('fewer')}>Show me fewer like this</button>
      </div>
      <span className="muted small" role="status">{status || (isGuest
        ? 'Feedback is saved to your guest session. “Show me fewer” excludes this article; “Not useful” reduces its topic.'
        : 'Useful feedback is saved on this device. Account recommendations continue to use your profile interests.')}</span>
    </section>
  );
}
