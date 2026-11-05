import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toPng } from 'html-to-image';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../../api';
import { keys, useSchedule, useSchedules, useStaff, useStore } from '../../api/queries';
import Icon from '../../components/Icon';
import PageHeader from '../../components/PageHeader';
import { Badge, Button, Card, cx, Loading, Segmented } from '../../components/ui';
import { autoFill } from '../../lib/autofill';
import { addMonths, datesBetween, hours, md, monthLabel, weeksOf, won } from '../../lib/time';
import { useValidation } from '../../lib/useValidation';
import { useEditor } from '../../store/editor';
import DayView from './DayView';
import MonthView from './MonthView';
import PartView from './PartView';
import PersonView from './PersonView';
import ShiftDialog, { type DialogTarget } from './ShiftDialog';

type View = 'month' | 'part' | 'person' | 'day';

export default function ScheduleEditor() {
  const id = Number(useParams().id);
  const [params, setParams] = useSearchParams();
  const view = (params.get('view') as View) || 'person';
  const qc = useQueryClient();

  const { data: store } = useStore();
  const { data: staff } = useStaff();
  const { data: loaded, error } = useSchedule(id);
  const { data: all } = useSchedules();
  const navigate = useNavigate();
  const { draft, dirty, past, future, load, edit, undo, redo, markSaved } = useEditor();
  const v = useValidation(draft, staff, store);
  const [dialog, setDialog] = useState<DialogTarget | null>(null);
  const gridRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (loaded && (draft?.id !== loaded.id || !dirty)) load(loaded);
    // 서버 데이터가 바뀔 때만 다시 불러온다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'z') return;
      if ((e.target as HTMLElement).closest('input, select, textarea')) return;
      e.preventDefault();
      if (e.shiftKey) redo();
      else undo();
    };
    const onUnload = (e: BeforeUnloadEvent) => {
      if (useEditor.getState().dirty) e.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('beforeunload', onUnload);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('beforeunload', onUnload);
    };
  }, [undo, redo]);

  const save = useMutation({
    mutationFn: () => api.saveSchedule(id, { shifts: draft!.shifts, leaves: draft!.leaves }),
    onSuccess: (s) => {
      markSaved(s);
      qc.setQueryData(keys.schedule(id), s);
      qc.invalidateQueries({ queryKey: keys.schedules, exact: true });
    },
  });
  const confirm = useMutation({
    mutationFn: async () => {
      if (useEditor.getState().dirty) await api.saveSchedule(id, { shifts: draft!.shifts, leaves: draft!.leaves });
      return api.confirmSchedule(id);
    },
    onSuccess: (s) => {
      markSaved(s);
      qc.setQueryData(keys.schedule(id), s);
      qc.invalidateQueries({ queryKey: keys.schedules, exact: true });
    },
  });
  const saveTemplate = useMutation({
    mutationFn: async () => {
      const name = window.prompt('템플릿 이름', `${monthLabel(draft!.periodStart)} 기본`);
      if (!name) return null;
      if (useEditor.getState().dirty) await save.mutateAsync();
      return api.saveTemplate(name, id);
    },
    onSuccess: (t) => t && qc.invalidateQueries({ queryKey: keys.templates }),
  });
  const remove = useMutation({
    mutationFn: () => api.deleteSchedule(id),
    onSuccess: () => {
      qc.removeQueries({ queryKey: keys.schedule(id) });
      qc.invalidateQueries({ queryKey: keys.schedules });
      navigate('/schedules', { replace: true });
    },
  });

  if (error) return <p className="text-sm text-danger">근무표를 불러오지 못했습니다: {String(error.message)}</p>;
  if (!draft || draft.id !== id || !staff || !store || !v) return <Loading />;

  const weeks = weeksOf(datesBetween(draft.periodStart, draft.periodEnd));
  const paramDate = params.get('date');
  const byDate = weeks.findIndex((w) => paramDate && w.includes(paramDate));
  const wi = byDate >= 0 ? byDate : Math.min(Number(params.get('week')) || 0, weeks.length - 1);
  const week = weeks[wi];
  const dates = week.filter((d): d is string => d !== null);
  const date = paramDate && dates.includes(paramDate) ? paramDate : dates[0];
  const setView = (next: View, d?: string) => setParams((p) => {
    p.set('view', next);
    if (d) p.set('date', d);
    return p;
  });
  const setWeek = (i: number) => setParams((p) => {
    p.set('week', String(i));
    p.delete('date');
    return p;
  });
  // 전월·익월 근무표. 한 달에 여럿이면(확정본 + 작성 중) 최근 것
  const monthSchedule = (n: number) =>
    all?.filter((s) => s.periodStart === addMonths(draft.periodStart, n)).sort((a, b) => b.id - a.id)[0];
  const goMonth = (n: number) => {
    const to = monthSchedule(n);
    if (!to || (dirty && !window.confirm('저장하지 않은 변경 사항이 사라집니다. 이동할까요?'))) return;
    navigate(`/schedules/${to.id}?view=${view}`);
  };
  const fullCount = staff.filter((s) => s.employmentType === 'FULL_TIME').length;

  const exportPng = async () => {
    if (!gridRef.current) return;
    const url = await toPng(gridRef.current, { backgroundColor: '#ffffff', pixelRatio: 2 });
    const a = document.createElement('a');
    a.href = url;
    a.download = `근무표_${draft.periodStart}.png`;
    a.click();
  };

  return (
    <div>
      <PageHeader
        title={
          <span className="flex items-center gap-1">
            {([-1, 1] as const).map((n) => (
              <button
                key={n}
                onClick={() => goMonth(n)}
                disabled={!monthSchedule(n)}
                aria-label={n < 0 ? '전월 근무표' : '익월 근무표'}
                title={monthSchedule(n) ? monthLabel(addMonths(draft.periodStart, n)) : `${monthLabel(addMonths(draft.periodStart, n))} 근무표 없음`}
                className={cx('grid size-8 place-items-center rounded-full text-ink hover:bg-bg disabled:opacity-30 disabled:hover:bg-transparent', n > 0 && 'order-last')}
              >
                <Icon name={n < 0 ? 'chevron-left' : 'chevron-right'} />
              </button>
            ))}
            <span>{monthLabel(draft.periodStart)} 근무표</span>
          </span>
        }
        badges={
          <>
            {draft.status === 'CONFIRMED' && <Badge tone="success">확정됨</Badge>}
            {v.tentativeCount > 0 && <Badge tone="danger">미정 {v.tentativeCount}건</Badge>}
            {dirty && <Badge>저장 안 됨</Badge>}
          </>
        }
        sub={`${draft.periodStart.replaceAll('-', '.')} – ${md(draft.periodEnd)} · 직원 ${fullCount}명(유동 근무) · 알바생 ${staff.length - fullCount}명(파트 근무)`}
        actions={
          <>
            <Segmented<View>
              value={view}
              onChange={(next) => setView(next)}
              options={[
                { value: 'month', label: '한 달' },
                { value: 'part', label: '파트별' },
                { value: 'person', label: '인원별' },
                { value: 'day', label: '일간' },
              ]}
            />
            <Button
              onClick={() =>
                edit((d) => {
                  d.shifts = autoFill(store, staff, datesBetween(d.periodStart, d.periodEnd), d.shifts, d.leaves, () => crypto.randomUUID());
                })
              }
              disabled={draft.status === 'CONFIRMED'}
              title="이미 넣은 근무는 두고, 직원 고정 근무와 알바생이 고른 파트를 채웁니다 (실행 취소 가능)"
            >
              자동 채우기
            </Button>
            <Button variant="ghost" size="sm" onClick={undo} disabled={!past.length} title="실행 취소 (⌘Z)" aria-label="실행 취소" className="w-8 px-0">
              <Icon name="undo" />
            </Button>
            <Button variant="ghost" size="sm" onClick={redo} disabled={!future.length} title="다시 실행 (⇧⌘Z)" aria-label="다시 실행" className="w-8 px-0">
              <Icon name="redo" />
            </Button>
            <Button onClick={() => save.mutate()} disabled={!dirty || save.isPending}>
              {save.isPending ? '저장 중…' : '저장'}
            </Button>
            <Button
              variant="primary"
              onClick={() => confirm.mutate()}
              disabled={!v.canConfirm || confirm.isPending || draft.status === 'CONFIRMED'}
              title={v.canConfirm ? '' : '시간 미정을 먼저 해결하세요'}
            >
              확정하기
            </Button>
          </>
        }
      />

      <div className={cx('mb-4 overflow-x-auto', view === 'month' && 'hidden')}>
        <Segmented<string>
          value={String(wi)}
          onChange={(i) => setWeek(Number(i))}
          options={weeks.map((w, i) => {
            const days = w.filter((d): d is string => d !== null);
            return { value: String(i), label: `${i + 1}주차 · ${md(days[0])}–${md(days[days.length - 1])}` };
          })}
        />
      </div>

      {view === 'day' ? (
        <DayView schedule={draft} staff={staff} store={store} dates={dates} date={date} onDate={(d) => setView('day', d)} onOpen={setDialog} />
      ) : (
        <div className="grid gap-4 min-[1760px]:grid-cols-[1fr_320px]">
          <div className="min-w-0">
            <Card className="overflow-x-auto">
              <div ref={gridRef} className="bg-surface">
                {view === 'month' ? (
                  <MonthView schedule={draft} staff={staff} store={store} onDate={(d) => setView('day', d)} />
                ) : view === 'person' ? (
                  <PersonView schedule={draft} staff={staff} store={store} v={v} dates={week} onOpen={setDialog} />
                ) : (
                  <PartView schedule={draft} staff={staff} store={store} dates={week} onOpen={setDialog} />
                )}
              </div>
            </Card>
            <Legend />
          </div>
          <SidePanel
            v={v}
            comment={draft.aiComment}
            actions={
              <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                  <Button size="sm" className="flex-1" onClick={exportPng}>
                    이미지 저장
                  </Button>
                  <Button size="sm" className="flex-1" onClick={() => saveTemplate.mutate()}>
                    템플릿으로 저장
                  </Button>
                </div>
                <Button
                  size="sm"
                  variant="danger"
                  disabled={remove.isPending}
                  onClick={() =>
                    window.confirm(
                      `${monthLabel(draft.periodStart)} 근무표를 삭제할까요?${draft.status === 'CONFIRMED' ? ' 확정된 근무표입니다.' : ''}${dirty ? ' 저장하지 않은 변경도 사라집니다.' : ''} 되돌릴 수 없습니다.`,
                    ) && remove.mutate()
                  }
                >
                  근무표 삭제
                </Button>
              </div>
            }
          />
        </div>
      )}

      {dialog && <ShiftDialog target={dialog} staff={staff} store={store} onClose={() => setDialog(null)} />}
      {(save.error || confirm.error || remove.error) && <p className="mt-3 text-sm text-danger">{String((save.error ?? confirm.error ?? remove.error)!.message)}</p>}
      {draft.status === 'CONFIRMED' && (
        <p className="mt-3 text-sm text-muted">
          확정된 근무표입니다. 수정 후 저장하면 변경 내역이 반영됩니다. <Link to="/schedules" className="underline underline-offset-2">목록으로</Link>
        </p>
      )}
    </div>
  );
}

function Legend() {
  const items: [string, string][] = [
    ['bg-open-bg', '오픈'],
    ['bg-middle-bg', '미들'],
    ['bg-close-bg', '마감'],
    ['bg-staff-bg', '직원 근무'],
    ['border-[1.5px] border-dashed border-staff', '시간 미정'],
    ['bg-brand-subtle', '연차·휴무'],
  ];
  return (
    <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs text-muted">
      <div className="flex flex-wrap gap-4">
        {items.map(([c, l]) => (
          <span key={l} className="flex items-center gap-1.5">
            <span className={cx('size-3 rounded', c)} />
            {l}
          </span>
        ))}
      </div>
      <span>블록 드래그로 이동 · 빈 칸 클릭으로 추가</span>
    </div>
  );
}

function SidePanel({ v, comment, actions }: { v: NonNullable<ReturnType<typeof useValidation>>; comment: string; actions: ReactNode }) {
  return (
    <aside className="grid content-start gap-4 lg:grid-cols-3 min-[1760px]:grid-cols-1">
      <Card className="grid grid-cols-2 gap-y-3 p-4 text-[13px]">
        <div>
          <div className="text-muted">총 근무시간</div>
          <div className="text-lg font-bold">{hours(v.totals.workMin)}</div>
        </div>
        <div>
          <div className="text-muted">예상 인건비</div>
          <div className="text-lg font-bold">{won(v.totals.cost)}</div>
        </div>
        <div>
          <div className="text-muted">주휴수당 대상</div>
          <div className="font-bold">알바생 {v.totals.weeklyHolidayCount}명</div>
        </div>
        <div>
          <div className="text-muted">주휴수당 합계</div>
          <div className="font-bold">{won(v.totals.holidayPay)}</div>
        </div>
        <div className="col-span-2">{actions}</div>
      </Card>

      {comment && (
        <Card className="p-4">
          <h3 className="text-sm font-bold">AI 브리핑</h3>
          <p className="mt-1.5 text-[13px] leading-relaxed text-muted">{comment}</p>
        </Card>
      )}
    </aside>
  );
}

