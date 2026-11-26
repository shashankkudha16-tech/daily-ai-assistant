const NOT_AVAILABLE = 'Not available in the provided text.';

export function explanationFallback(key) {
  return ({
    whyItHappened: 'The article does not say why this happened.',
    importance: 'The available source does not explain why this update is important.',
    usefulToReader: 'The available source does not describe a specific personal benefit.',
  })[key] || NOT_AVAILABLE;
}
