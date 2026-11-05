// 회원가입·로그인. VITE_USE_MOCK=false면 서버(/api/auth/*)가 JWT를 쿠키로 내려준다고 본다
import type { User } from '../types';

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false';
const USERS = 'autoschedule-users';

interface StoredUser extends User {
  salt: string;
  hash: string;
}

async function hash(password: string, salt: string) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${salt}:${password}`));
  return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');
}

const readUsers = (): StoredUser[] => {
  try {
    return JSON.parse(localStorage.getItem(USERS) ?? '[]');
  } catch {
    return [];
  }
};

async function post(path: string, body: unknown): Promise<User> {
  const res = await fetch(`/api/auth/${path}`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!res.ok) throw new Error((await res.text()) || `${res.status} ${res.statusText}`);
  return res.json();
}

/**
 * shortcut: mock 계정은 이 브라우저 localStorage에만 있고 매장 데이터도 계정끼리 같이 쓴다.
 * 진짜 인증·계정별 데이터는 백엔드(JWT)에서 한다.
 */
export const auth = {
  async signup(name: string, email: string, password: string): Promise<User> {
    if (!USE_MOCK) return post('signup', { name, email, password });
    const users = readUsers();
    const key = email.trim().toLowerCase();
    if (users.some((u) => u.email === key)) throw new Error('이미 가입된 이메일입니다.');
    const salt = crypto.randomUUID();
    const user = { name: name.trim(), email: key };
    localStorage.setItem(USERS, JSON.stringify([...users, { ...user, salt, hash: await hash(password, salt) }]));
    return user;
  },
  /** 서버 쿠키(리프레시 토큰)를 지운다. 실패해도 화면은 로그아웃한다 */
  async logout(): Promise<void> {
    if (!USE_MOCK) await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }).catch(() => undefined);
  },
  async login(email: string, password: string): Promise<User> {
    if (!USE_MOCK) return post('login', { email, password });
    const u = readUsers().find((x) => x.email === email.trim().toLowerCase());
    if (!u || u.hash !== (await hash(password, u.salt))) throw new Error('이메일 또는 비밀번호가 맞지 않습니다.');
    return { name: u.name, email: u.email };
  },
};
