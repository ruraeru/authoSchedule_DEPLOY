import { useQueryClient } from '@tanstack/react-query';
import { Navigate, NavLink, Outlet, useLocation } from 'react-router-dom';
import { auth } from '../api/auth';
import { useStore } from '../api/queries';
import { useAuth } from '../store/auth';
import Icon, { Mark, type IconName } from './Icon';
import { cx } from './ui';

const NAV: { to: string; label: string; icon: IconName; end?: boolean }[] = [
  { to: '/', label: '대시보드', icon: 'dashboard', end: true },
  { to: '/schedules', label: '근무표', icon: 'calendar' },
  { to: '/staff', label: '직원·알바생 관리', icon: 'users' },
  { to: '/templates', label: '템플릿', icon: 'copy' },
  { to: '/settings', label: '매장 설정', icon: 'settings' },
];

export default function Layout() {
  const { data: store } = useStore();
  const { user, setUser } = useAuth();
  const qc = useQueryClient();
  const { pathname, search } = useLocation();
  const logout = async () => {
    await auth.logout();
    setUser(null);
    qc.clear();
  };
  // 로그인 안 했으면 로그인 화면으로, 끝나면 원래 가려던 곳으로 돌아온다
  if (!user) return <Navigate to="/login" replace state={{ from: pathname + search }} />;
  // 사이드바와 바깥은 회색 틀(bg), 본문은 흰 시트를 위·오른쪽·아래 8px 띄워 올린다
  return (
    <div className="min-h-screen md:flex">
      <aside className="bg-bg md:sticky md:top-0 md:flex md:h-screen md:w-60 md:shrink-0 md:flex-col md:px-4 md:pt-5 md:pb-4">
        <div className="flex items-center gap-2.5 px-4 pt-4 pb-3 md:px-2 md:pt-1.5 md:pb-5">
          <Mark />
          <div className="min-w-0 leading-tight">
            <div className="text-sm font-medium">오토스케줄</div>
            <div className="truncate text-xs text-muted">{store ? [store.name, store.branch].filter(Boolean).join(' · ') : ' '}</div>
          </div>
        </div>
        <nav className="flex gap-0.5 overflow-x-auto px-3 pb-3 md:flex-col md:px-0">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.end}
              className={({ isActive }) =>
                cx(
                  'flex h-9 shrink-0 items-center gap-2.5 rounded-[10px] px-2.5 text-sm',
                  isActive ? 'bg-brand-subtle font-medium text-ink' : 'text-muted hover:bg-brand-subtle/60 hover:text-ink',
                )
              }
            >
              <Icon name={n.icon} size={18} />
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="flex items-center gap-2.5 px-4 py-3 md:mt-auto md:px-2 md:py-2">
          <span className="grid size-8 shrink-0 place-items-center rounded-full bg-staff-bg text-xs font-medium text-staff">{user.name[0]}</span>
          <div className="min-w-0 flex-1 leading-tight">
            <div className="truncate text-sm font-medium">{user.name}</div>
            <div className="truncate text-xs text-muted">{user.email}</div>
          </div>
          <button onClick={logout} className="shrink-0 rounded-full p-1.5 text-muted hover:bg-brand-subtle hover:text-ink" aria-label="로그아웃" title="로그아웃">
            <Icon name="logout" size={18} />
          </button>
        </div>
      </aside>
      <div className="min-w-0 flex-1 md:py-2 md:pr-2">
        <main className="min-h-screen bg-surface px-4 py-6 md:min-h-[calc(100vh-16px)] md:rounded-2xl md:border md:border-line md:px-8 md:py-7">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
