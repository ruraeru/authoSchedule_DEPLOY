import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { auth } from '../api/auth';
import { Mark } from '../components/Icon';
import { Button, Card, Field, inputClass } from '../components/ui';
import { useAuth } from '../store/auth';

/** 로그인·회원가입 한 화면. mode로 나눈다 */
export default function AuthPage({ mode }: { mode: 'login' | 'signup' }) {
  const signup = mode === 'signup';
  const { user, setUser } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  // 가드가 넘겨준 원래 가려던 곳
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/';
  const [form, setForm] = useState({ name: '', email: '', password: '', confirm: '' });
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = useMutation({
    mutationFn: () => (signup ? auth.signup(form.name, form.email, form.password) : auth.login(form.email, form.password)),
    onSuccess: (u) => {
      // 이전 계정의 매장·근무표 캐시가 보이지 않게
      qc.clear();
      setUser(u);
      navigate(from, { replace: true });
    },
  });

  if (user) return <Navigate to={from} replace />;
  const mismatch = signup && form.confirm !== '' && form.confirm !== form.password;
  const short = signup && form.password !== '' && form.password.length < 8;
  const ready = form.email.trim() && form.password && (!signup || (form.name.trim() && form.password.length >= 8 && form.password === form.confirm));
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (ready) submit.mutate();
  };

  return (
    <div className="grid min-h-screen place-items-center bg-bg px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center justify-center gap-2.5">
          <Mark size={36} />
          <span className="text-lg font-bold">오토스케줄</span>
        </div>
        <Card className="p-6">
          <h1 className="text-lg font-bold">{signup ? '회원가입' : '로그인'}</h1>
          <p className="mt-1 text-sm text-muted">{signup ? '이메일로 가입하고 매장 근무표를 만들어 보세요.' : '이메일과 비밀번호를 입력하세요.'}</p>
          <form onSubmit={onSubmit} className="mt-5 space-y-3.5">
            {signup && (
              <Field label="이름">
                <input className={inputClass} autoComplete="name" value={form.name} onChange={set('name')} />
              </Field>
            )}
            <Field label="이메일">
              <input type="email" className={inputClass} autoComplete="email" value={form.email} onChange={set('email')} />
            </Field>
            <Field label="비밀번호" hint={short ? '8자 이상 입력하세요.' : undefined}>
              <input type="password" className={inputClass} autoComplete={signup ? 'new-password' : 'current-password'} value={form.password} onChange={set('password')} />
            </Field>
            {signup && (
              <Field label="비밀번호 확인" hint={mismatch ? '비밀번호가 다릅니다.' : undefined}>
                <input type="password" className={inputClass} autoComplete="new-password" value={form.confirm} onChange={set('confirm')} />
              </Field>
            )}
            {submit.error && (
              <p role="alert" className="text-sm text-danger">
                {submit.error.message}
              </p>
            )}
            <Button type="submit" variant="primary" className="w-full" disabled={!ready || submit.isPending}>
              {submit.isPending ? '처리 중…' : signup ? '가입하기' : '로그인'}
            </Button>
          </form>
        </Card>
        <p className="mt-4 text-center text-sm text-muted">
          {signup ? '이미 계정이 있나요? ' : '처음이신가요? '}
          <Link to={signup ? '/login' : '/signup'} state={{ from }} className="font-medium text-ink underline underline-offset-2">
            {signup ? '로그인' : '회원가입'}
          </Link>
        </p>
      </div>
    </div>
  );
}
