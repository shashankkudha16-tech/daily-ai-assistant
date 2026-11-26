export function sourceNote(type, completeness) {
  if (completeness === 'TRUNCATED') return 'Only part of the available source text was processed; the extracted text was cut at the configured limit.';
  if (completeness === 'UNKNOWN') return 'Completeness not verified. Check the original publisher article for full context.';
  if (completeness === 'PARTIAL' && type === 'EXCERPT') return 'Based on a partial publisher excerpt; the full article may include more information.';
  return ({
    FULL_ARTICLE: completeness === 'COMPLETE'
      ? 'Based on the complete article body extracted from the publisher page.'
      : 'Article body extracted from the publisher page; completeness is not verified.',
    RSS_CONTENT: 'Based on publisher-provided RSS content; its scope may be partial.',
    EXCERPT: 'Based on a short publisher excerpt; the full article may include more information.',
    TITLE_ONLY: 'Only the headline was available, so this explanation is limited.',
  })[type] || 'Source length may be limited. Check the original publisher article for full context.';
}
