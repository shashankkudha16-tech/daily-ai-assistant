import { useEffect, useState } from 'react';
import { Route, Routes } from 'react-router-dom';
import Layout from './components/Layout.jsx';
import HomePage from './pages/HomePage.jsx';
import DiscoverPage from './pages/DiscoverPage.jsx';
import ArticlePage from './pages/ArticlePage.jsx';
import LearnPage from './pages/LearnPage.jsx';
import TopicPage from './pages/TopicPage.jsx';
import BookmarksPage from './pages/BookmarksPage.jsx';
import ProfilePage from './pages/ProfilePage.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';
import LoginPage from './pages/LoginPage.jsx';
import RegisterPage from './pages/RegisterPage.jsx';
import ProgressPage from './pages/ProgressPage.jsx';
import HistoryPage from './pages/HistoryPage.jsx';
import MentorPage from './pages/MentorPage.jsx';
import { ProtectedRoute, PublicOnlyRoute } from './auth/ProtectedRoute.jsx';

function initialTheme() {
  const stored = localStorage.getItem('dailyai-theme');
  if (stored === 'dark' || stored === 'light') return stored;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export default function App() {
  const [theme, setTheme] = useState(initialTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('dailyai-theme', theme);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#0b1220' : '#f7f8fb');
  }, [theme]);

  return (
    <Routes>
      <Route path="login" element={<PublicOnlyRoute><LoginPage /></PublicOnlyRoute>} />
      <Route path="register" element={<PublicOnlyRoute><RegisterPage /></PublicOnlyRoute>} />
      <Route element={<Layout theme={theme} onToggleTheme={() => setTheme(theme === 'dark' ? 'light' : 'dark')} />}>
        <Route index element={<HomePage />} />
        <Route path="discover" element={<DiscoverPage />} />
        <Route path="news/:id" element={<ArticlePage />} />
        <Route path="learn" element={<ProtectedRoute><LearnPage /></ProtectedRoute>} />
        <Route path="learn/:id" element={<ProtectedRoute><TopicPage /></ProtectedRoute>} />
        <Route path="progress" element={<ProtectedRoute><ProgressPage /></ProtectedRoute>} />
        <Route path="mentor" element={<ProtectedRoute><MentorPage /></ProtectedRoute>} />
        <Route path="history" element={<ProtectedRoute><HistoryPage /></ProtectedRoute>} />
        <Route path="settings" element={<ProtectedRoute><ProfilePage /></ProtectedRoute>} />
        <Route path="bookmarks" element={<ProtectedRoute><BookmarksPage /></ProtectedRoute>} />
        <Route path="profile" element={<ProtectedRoute><ProfilePage /></ProtectedRoute>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
