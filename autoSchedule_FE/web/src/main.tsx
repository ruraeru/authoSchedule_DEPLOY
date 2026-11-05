import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import Layout from './components/Layout';
import './index.css';
import Dashboard from './pages/Dashboard';
import AuthPage from './pages/Auth';
import CreateSchedule from './pages/CreateSchedule';
import Generate from './pages/Generate';
import NotFound from './pages/NotFound';
import ScheduleEditor from './pages/schedule/ScheduleEditor';
import Schedules from './pages/Schedules';
import Settings from './pages/Settings';
import StaffPage from './pages/Staff';
import Templates from './pages/Templates';

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } });

const router = createBrowserRouter([
  { path: '/login', element: <AuthPage mode="login" /> },
  { path: '/signup', element: <AuthPage mode="signup" /> },
  {
    element: <Layout />,
    children: [
      { path: '/', element: <Dashboard /> },
      { path: '/schedules', element: <Schedules /> },
      { path: '/schedules/new', element: <Generate /> },
      { path: '/schedules/manual', element: <CreateSchedule /> },
      { path: '/schedules/:id', element: <ScheduleEditor /> },
      { path: '/staff', element: <StaffPage /> },
      { path: '/templates', element: <Templates /> },
      { path: '/settings', element: <Settings /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
