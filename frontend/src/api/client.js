import axios from 'axios';
import { resolveApiBaseUrl } from './baseUrl';

/**
 * Requests use relative /api paths by default.
 * - Development: Vite proxies /api to http://localhost:8080 (vite.config.js).
 * - Production: the Railway frontend Nginx proxy forwards /api/* to the backend,
 *   so the browser stays same-origin and the session cookie stays first-party.
 * A packaged Android build has no proxy, so VITE_API_BASE_URL must be provided
 * at build time (for example http://10.0.2.2:8080 or a public HTTPS URL).
 */
const baseURL = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);

const client = axios.create({
  baseURL,
  timeout: 20000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
});

let csrfToken = null;

/** Session-backed CSRF token is fetched before any mutating request. */
export async function ensureCsrfToken(force = false) {
  if (csrfToken && !force) return csrfToken;
  const { data } = await client.get('/api/auth/csrf');
  csrfToken = data.token;
  return csrfToken;
}

export function clearCsrfToken() {
  csrfToken = null;
}

client.interceptors.request.use(async (config) => {
  const method = (config.method || 'get').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = await ensureCsrfToken();
    config.headers = config.headers || {};
    config.headers['X-XSRF-TOKEN'] = token;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status;
    const url = error?.config?.url || '';
    if (status === 401) {
      csrfToken = null;
      if (!url.includes('/api/auth/login') && !url.includes('/api/auth/me')) {
        window.dispatchEvent(new CustomEvent('dailyai:unauthorized'));
      }
    }
    if (status === 403 && url.includes('csrf')) {
      csrfToken = null;
    }
    return Promise.reject(error);
  },
);

export function apiErrorMessage(error) {
  const details = error?.response?.data?.details;
  if (Array.isArray(details) && details.length) {
    return details.join(' ');
  }
  if (error?.response?.data?.message) {
    return error.response.data.message;
  }
  if (error?.response?.status === 429) {
    return 'Too many attempts. Please wait before trying again.';
  }
  if (error?.response?.status) {
    return `The server responded with status ${error.response.status}.`;
  }
  if (error?.code === 'ECONNABORTED') {
    return 'The request timed out. Check that the backend is running.';
  }
  if (error?.message === 'Network Error') {
    return 'Cannot reach the backend. Check the API URL and your network connection.';
  }
  return error?.message || 'Something went wrong.';
}

export default client;
