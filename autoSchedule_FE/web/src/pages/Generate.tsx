import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { keys, useStaff, useStore } from '../api/queries';
import Icon from '../components/Icon';
import PageHeader from '../components/PageHeader';
import { Button, Card, cx, Loading } from '../components/ui';
import { addMonths, md, monthEnd, monthLabel, monthOf, ymd } from '../lib/time';
import type { GenerateRequest } from '../types';

const STEPS = ['매장 조건·가능 시간 분석', '직원 출근 시간 배치', '알바생 파트 배치', '근로 규칙 검증·보정'];
/** 각 단계가 시작되는 진행률(%) */
const STEP_AT = [0, 25, 55, 80];

/**
 * shortcut: AI는 한 번에 답을 돌려줘서 실제 진행률을 알 수 없다. 걸린 시간으로 추정한다
 * (실측 1~3분: 60초에 약 60%, 2분에 약 82%, 끝나기 전엔 95%를 넘지 않음). 실제 진행률이 필요하면 스트리밍 응답으로 바꾼다.
 */
const estimate = (sec: number) => 95 * (1 - Math.exp(-sec / 60));

export function Choice({ active, onClick, title, desc }: { active: boolean; onClick: () => void; title: string; desc: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cx('rounded-[10px] border bg-surface px-4 py-3.5 text-left', active ? 'border-ink ring-[0.5px] ring-ink' : 'border-line-strong hover:bg-bg')}
    >
      <div className="flex items-center justify-between gap-2 text-sm font-medium">
        {title}
        {active && <Icon name="check" />}
      </div>
      <div className="mt-1 text-xs text-muted">{desc}</div>
    </button>
  );
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h2 className="mb-2.5 text-sm font-bold">{title}</h2>
      {children}
    </div>
  );
}

export default function Generate() {
  const { data: staff } = useStaff();
  const { data: store } = useStore();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const thisMonth = monthOf(ymd(new Date()));
  const months = [0, 1, 2, 3].map((i) => addMonths(thisMonth, i));
  const [req, setReq] = useState<GenerateRequest>({
    periodStart: months[1],
    weeklyHolidayPay: 'MINIMIZE',
    priority: 'FAIRNESS',
  });
  const [elapsed, setElapsed] = useState(0);

  const gen = useMutation({
    mutationFn: api.generateSchedule,
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: keys.schedules });
      navigate(`/schedules/${s.id}`);
    },
  });

  useEffect(() => {
    if (!gen.isPending) return setElapsed(0);
    const started = Date.now();
    const t = setInterval(() => setElapsed((Date.now() - started) / 1000), 500);
    return () => clearInterval(t);
  }, [gen.isPending]);
  const pct = estimate(elapsed);
  const step = STEP_AT.filter((at) => pct >= at).length - 1;

  if (!staff || !store) return <Loading />;
  const fullTime = staff.filter((s) => s.employmentType === 'FULL_TIME');
  const set = (p: Partial<GenerateRequest>) => setReq((r) => ({ ...r, ...p }));

  if (gen.isPending)
    return (
      <Card className="mx-auto mt-10 max-w-md p-8">
        <h1 className="text-lg font-bold">AI가 근무표를 만들고 있어요</h1>
        <p className="mt-1 text-sm text-muted">{monthLabel(req.periodStart)} · 직원 {fullTime.length}명 · 알바생 {staff.length - fullTime.length}명 · 보통 1~2분 걸려요</p>
        <div className="mt-6">
          <div
            role="progressbar"
            aria-label="근무표 생성 진행률(추정)"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(pct)}
            className="h-1.5 overflow-hidden rounded-full bg-brand-subtle"
          >
            <div className="h-full rounded-full bg-brand transition-[width] duration-500 ease-out" style={{ width: `${pct}%` }} />
          </div>
          <div className="mt-1.5 flex justify-between text-xs text-muted tabular-nums">
            <span>{Math.round(pct)}%</span>
            <span>
              {Math.floor(elapsed / 60)}:{String(Math.floor(elapsed % 60)).padStart(2, '0')} 경과
            </span>
          </div>
        </div>
        <ol className="mt-6 space-y-3">
          {STEPS.map((s, i) => (
            <li key={s} className={cx('flex items-center gap-3 text-sm', i > step && 'text-muted')}>
              <span
                className={cx(
                  'grid size-6 place-items-center rounded-full text-xs',
                  i < step ? 'bg-brand-subtle text-ink' : i === step ? 'animate-pulse bg-ink text-white' : 'border-[1.5px] border-line-strong',
                )}
              >
                {i < step ? <Icon name="check" size={14} /> : i === step ? <span className="size-1.5 rounded-full bg-white" /> : null}
              </span>
              {s}
            </li>
          ))}
        </ol>
      </Card>
    );

  return (
    <div className="max-w-3xl">
      <PageHeader title="AI 근무표 생성" sub="조건을 고르면 AI가 직원 근무를 정하고 알바생은 고른 파트에 배치합니다. 구성원 이름은 빼고 번호로만 전달합니다." />
      <Card className="space-y-7 p-6">
        <Section title="기간">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {months.map((m) => (
              <Choice key={m} active={req.periodStart === m} onClick={() => set({ periodStart: m })} title={monthLabel(m)} desc={`${md(m)} – ${md(monthEnd(m))}`} />
            ))}
          </div>
        </Section>
        <Section title="주휴수당">
          <div className="grid gap-2 sm:grid-cols-2">
            <Choice active={req.weeklyHolidayPay === 'MINIMIZE'} onClick={() => set({ weeklyHolidayPay: 'MINIMIZE' })} title="최소화" desc="알바생을 가능하면 주 15시간 미만으로 배치" />
            <Choice active={req.weeklyHolidayPay === 'ALLOW'} onClick={() => set({ weeklyHolidayPay: 'ALLOW' })} title="허용" desc="주휴수당은 피하지 않고 계산만" />
          </div>
        </Section>
        <Section title="우선순위">
          <div className="grid gap-2 sm:grid-cols-2">
            <Choice active={req.priority === 'FAIRNESS'} onClick={() => set({ priority: 'FAIRNESS' })} title="공평 배분" desc="알바생 간 근무시간 차이를 줄임" />
            <Choice active={req.priority === 'COST'} onClick={() => set({ priority: 'COST' })} title="인건비 절감" desc="필요 인원만큼만 최소 배치" />
          </div>
        </Section>
        {gen.error && <p className="text-sm text-danger">생성 실패: {gen.error.message}</p>}
        <div className="flex items-center justify-end gap-3">
          {!staff.length && <span className="text-sm text-muted">직원·알바생을 먼저 등록하세요.</span>}
          <Button variant="primary" onClick={() => gen.mutate(req)} disabled={!staff.length}>
            근무표 생성
          </Button>
        </div>
      </Card>
    </div>
  );
}
