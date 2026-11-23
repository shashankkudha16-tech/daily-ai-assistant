import client from './client';

export const getMe = () => client.get('/api/auth/me').then((r) => r.data);
export const establishGuestSession = () => client.get('/api/guest/session').then((r) => r.data);
export const getGuestRecommendations = (limit = 12) =>
  client.post('/api/guest/recommendations', null, { params: { limit } }).then((r) => r.data);
export const recordGuestInteraction = (articleId, type) =>
  client.post('/api/guest/interactions', { articleId, type });
export const saveRecommendationFeedback = (articleId, action) =>
  client.patch('/api/guest/feedback', { articleId, action }).then((r) => r.data);
export const getRecommendationFeedback = (articleId) =>
  client.get(`/api/guest/feedback/${articleId}`).then((r) => (r.data.action || '').toLowerCase().replaceAll('_', '-'));
export const recordGuestSearch = (query) => client.post('/api/guest/search', { query });
export const resetGuestPersonalization = () => client.delete('/api/guest/personalization');
export const login = (email, password) => client.post('/api/auth/login', { email, password }).then((r) => ({
  user: r.data,
  guestMigration: r.headers['x-guest-migration-status'] || 'NO_GUEST',
}));
export const register = (payload) => client.post('/api/auth/register', payload).then((r) => ({
  user: r.data,
  guestMigration: r.headers['x-guest-migration-status'] || 'NO_GUEST',
}));
export const logout = () => client.post('/api/auth/logout');

export const getDashboard = () => client.get('/api/dashboard').then((r) => r.data);

export const getNews = (params = {}) => client.get('/api/news', { params }).then((r) => r.data);
export const getLatestNews = (limit = 5) =>
  client.get('/api/news/latest', { params: { limit } }).then((r) => r.data);
export const getArticle = (id) => client.get(`/api/news/${id}`).then((r) => r.data);
export const searchNews = (params) =>
  client.get('/api/news/search', { params }).then((r) => r.data);
export const getCategories = () => client.get('/api/categories').then((r) => r.data);

export const getRoadmap = () => client.get('/api/learning/roadmap').then((r) => r.data);
export const getTopic = (id) => client.get(`/api/learning/topics/${id}`).then((r) => r.data);
export const updateProgress = (topicId, status) =>
  client.post('/api/learning/progress', { topicId, status }).then((r) => r.data);
export const getDailyPlan = () => client.get('/api/learning/daily-plan').then((r) => r.data);
export const completeDailyTask = (taskId, completed = true) =>
  client
    .post(`/api/learning/daily-plan/${taskId}`, null, { params: { completed } })
    .then((r) => r.data);

export const getTools = (params = {}) => client.get('/api/tools', { params }).then((r) => r.data);
export const getTool = (id) => client.get(`/api/tools/${id}`).then((r) => r.data);
export const getToolCategories = () => client.get('/api/tools/categories').then((r) => r.data);

export const getBookmarks = (itemType) =>
  client.get('/api/bookmarks', { params: itemType ? { itemType } : {} }).then((r) => r.data);
export const addBookmark = (itemType, itemId) =>
  client.post('/api/bookmarks', { itemType, itemId }).then((r) => r.data);
export const removeBookmark = (itemType, itemId) =>
  client.delete('/api/bookmarks', { params: { itemType, itemId } });

export const getProfile = () => client.get('/api/profile').then((r) => r.data);
export const updateProfilePreferences = (preferences) =>
  client.put('/api/profile/preferences', { preferences }).then((r) => r.data);
export const updateProfileName = (name) =>
  client.put('/api/profile', { name }).then((r) => r.data);
export const getHistory = (limit = 20) =>
  client.get('/api/profile/history', { params: { limit } }).then((r) => r.data);

export const refreshNews = () => client.post('/api/admin/news/refresh').then((r) => r.data);
