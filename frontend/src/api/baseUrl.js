/**
 * Resolves the API base URL used by the axios client.
 *
 * - An explicit VITE_API_BASE_URL always wins. Packaged Android builds and
 *   split-origin deployments need it because they have no dev proxy or rewrite.
 * - Otherwise the base URL is empty, so axios issues relative requests such as
 *   /api/news and the cookie stays on the page's own origin.
 * - Development: Vite proxies /api to http://localhost:8080 (vite.config.js).
 * - Production: the Railway Nginx config proxies /api to the backend.
 * - Production never falls back to an absolute localhost URL.
 */
export function resolveApiBaseUrl(configuredValue) {
  if (typeof configuredValue !== 'string') {
    return '';
  }
  return configuredValue.trim().replace(/\/+$/, '');
}
