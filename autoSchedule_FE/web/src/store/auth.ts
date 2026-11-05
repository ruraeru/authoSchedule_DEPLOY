// 로그인한 사용자. 새로고침해도 유지
import { create } from 'zustand';
import type { User } from '../types';

const KEY = 'autoschedule-session';

const read = (): User | null => {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? 'null');
  } catch {
    return null;
  }
};

export const useAuth = create<{ user: User | null; setUser: (u: User | null) => void }>((set) => ({
  user: read(),
  setUser: (user) => {
    try {
      if (user) localStorage.setItem(KEY, JSON.stringify(user));
      else localStorage.removeItem(KEY);
    } catch {
      // 저장 실패해도 이번 탭에선 로그인 상태 유지
    }
    set({ user });
  },
}));
