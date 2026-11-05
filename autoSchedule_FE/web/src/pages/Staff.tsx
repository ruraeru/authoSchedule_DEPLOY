import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api';
import { keys, useStaff, useStore } from '../api/queries';
import Icon from '../components/Icon';
import PageHeader from '../components/PageHeader';
import { Avatar, Badge, Button, Card, cx, Field, inputBase, inputClass, Loading, Segmented } from '../components/ui';
import { sortParts } from '../lib/rules';
import { DAY_LABELS } from '../lib/time';
import type { EmploymentType, Part, Staff, WorkTime } from '../types';

type Filter = 'ALL' | EmploymentType;

const PART_TONE: Record<string, string> = { open: 'bg-open-bg text-open', middle: 'bg-middle-bg text-middle', close: 'bg-close-bg text-close', staff: 'bg-staff-bg text-staff' };

const DEFAULT_TIME: WorkTime = { start: '09:00', end: '18:00' };
// 기본은 월~금 09–18
const defaultHours = () => DAY_LABELS.map((_, i) => (i < 5 ? { ...DEFAULT_TIME } : null));

const blank = (type: EmploymentType): Staff => ({
  id: 0,
  name: '',
  employmentType: type,
  title: type === 'FULL_TIME' ? '정직원' : '알바',
  hourlyWage: 10320,
  isMinor: false,
  availableParts: Array.from({ length: 7 }, () => []),
  workHours: type === 'FULL_TIME' ? defaultHours() : null,
  phone: '',
});

export default function StaffPage() {
  const { data: staff } = useStaff();
  const { data: store } = useStore();
  const [filter, setFilter] = useState<Filter>('ALL');
  const [editing, setEditing] = useState<Staff | null>(null);

  if (!staff || !store) return <Loading />;
  const list = staff.filter((s) => filter === 'ALL' || s.employmentType === filter);
  const count = (t: EmploymentType) => staff.filter((s) => s.employmentType === t).length;

  return (
    <div>
      <PageHeader
        title="직원·알바생 관리"
        sub="직원은 요일별 고정 근무 시각으로, 알바생은 파트 단위로 배치합니다."
        actions={
          <Button variant="primary" onClick={() => setEditing(blank(filter === 'FULL_TIME' ? 'FULL_TIME' : 'PART_TIME'))}>
            <Icon name="plus" />
            구성원 추가
          </Button>
        }
      />
      <div className="mb-4">
        <Segmented<Filter>
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'ALL', label: `전체 ${staff.length}` },
            { value: 'FULL_TIME', label: `직원 ${count('FULL_TIME')}` },
            { value: 'PART_TIME', label: `알바생 ${count('PART_TIME')}` },
          ]}
        />
      </div>

      <div className={cx('grid items-start gap-4', editing && 'xl:grid-cols-[1fr_420px]')}>
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[640px] text-sm">
            <thead className="border-b border-line text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-normal">이름</th>
                <th className="px-4 py-3 font-normal">고용 형태</th>
                <th className="px-4 py-3 text-right font-normal">시급</th>
                <th className="px-4 py-3 font-normal">연락처</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {list.map((s) => (
                <tr key={s.id} onClick={() => setEditing(structuredClone(s))} className={cx('cursor-pointer hover:bg-bg', editing?.id === s.id && 'bg-brand-subtle')}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2.5">
                      <Avatar name={s.name} id={s.id} />
                      <span className="font-medium">{s.name}</span>
                      {s.isMinor && <Badge>연소자</Badge>}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    {s.employmentType === 'FULL_TIME' ? <Badge tone="staff">직원 · {s.title}</Badge> : <span className="text-muted">알바 · 파트 근무</span>}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">{s.hourlyWage.toLocaleString()}원</td>
                  <td className="px-4 py-3 text-muted">{s.phone}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        {editing && <StaffForm key={editing.id || 'new'} initial={editing} parts={sortParts(store.parts)} minWage={store.minimumWage} onClose={() => setEditing(null)} />}
      </div>
    </div>
  );
}

function StaffForm({ initial, parts, minWage, onClose }: { initial: Staff; parts: Part[]; minWage: number; onClose: () => void }) {
  const qc = useQueryClient();
  const [s, setS] = useState(initial);
  const set = (p: Partial<Staff>) => setS((x) => ({ ...x, ...p }));
  const done = () => {
    qc.invalidateQueries({ queryKey: keys.staff });
    qc.invalidateQueries({ queryKey: keys.schedules });
    // 구성원 수가 바뀌면 자동 판정 규모도 바뀐다
    qc.invalidateQueries({ queryKey: keys.store });
    onClose();
  };
  const save = useMutation({ mutationFn: () => api.saveStaff(s), onSuccess: done });
  const remove = useMutation({ mutationFn: () => api.deleteStaff(s.id), onSuccess: done });
  const full = s.employmentType === 'FULL_TIME';
  const wh = s.workHours ?? defaultHours();
  const noDay = wh.every((w) => !w);
  const badHours = full && (noDay || wh.some((w) => w && w.start >= w.end));
  const setDay = (d: number, w: WorkTime | null) => set({ workHours: wh.map((x, i) => (i === d ? w : x)) });
  // 새로 켠 요일은 바로 앞 근무 요일 시각을 따른다
  const prevTime = (d: number) => wh.slice(0, d).reverse().find(Boolean) ?? wh.find(Boolean) ?? DEFAULT_TIME;

  const setType = (t: EmploymentType) =>
    set({
      employmentType: t,
      title: t === 'FULL_TIME' ? '정직원' : s.isMinor ? '연소자' : '알바',
      workHours: t === 'FULL_TIME' ? (s.workHours ?? defaultHours()) : null,
      isMinor: t === 'FULL_TIME' ? false : s.isMinor,
    });
  // 순서는 시작 시각 순
  const ids = parts.map((p) => p.id);
  const toggle = (d: number, id: string) =>
    set({ availableParts: s.availableParts.map((row, i) => (i !== d ? row : ids.filter((x) => (x === id ? !row.includes(x) : row.includes(x))))) });
  const toggleDay = (d: number) => set({ availableParts: s.availableParts.map((row, i) => (i !== d ? row : row.length ? [] : ids)) });
  // 모든 요일에 켜져 있으면 다 끄고, 아니면 다 켠다
  const togglePart = (id: string) => {
    const on = !s.availableParts.every((row) => row.includes(id));
    set({ availableParts: s.availableParts.map((row) => ids.filter((x) => (x === id ? on : row.includes(x)))) });
  };

  return (
    <Card className="space-y-4 p-5">
      <div className="flex items-center justify-between">
        <h2 className="font-bold">{s.id ? `${initial.name} 정보` : '구성원 추가'}</h2>
        <button onClick={onClose} className="text-muted hover:text-ink" aria-label="닫기">
          <Icon name="x" size={18} />
        </button>
      </div>
      <Segmented<EmploymentType>
        value={s.employmentType}
        onChange={setType}
        options={[
          { value: 'PART_TIME', label: '알바생 (파트 근무)' },
          { value: 'FULL_TIME', label: '직원' },
        ]}
      />
      <div className="grid grid-cols-2 gap-3">
        <Field label="이름">
          <input className={inputClass} value={s.name} onChange={(e) => set({ name: e.target.value })} />
        </Field>
        <Field label={full ? '직함' : '연락처'}>
          {full ? (
            <input className={inputClass} value={s.title} onChange={(e) => set({ title: e.target.value })} placeholder="매니저, 정직원…" />
          ) : (
            <input className={inputClass} value={s.phone} onChange={(e) => set({ phone: e.target.value })} />
          )}
        </Field>
        <Field label={full ? '통상시급' : '시급'} hint={s.hourlyWage < minWage ? `최저임금 ${minWage.toLocaleString()}원 미만` : undefined}>
          <input type="number" className={inputClass} value={s.hourlyWage} step={10} onChange={(e) => set({ hourlyWage: Number(e.target.value) })} />
        </Field>
        {full && (
          <Field label="연락처">
            <input className={inputClass} value={s.phone} onChange={(e) => set({ phone: e.target.value })} />
          </Field>
        )}
      </div>
      {!full && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" className="size-4 accent-brand" checked={s.isMinor} onChange={(e) => set({ isMinor: e.target.checked, title: e.target.checked ? '연소자' : '알바' })} />
          연소근로자 (만 18세 미만 · 1일 7시간, 22시 이후 근무 불가)
        </label>
      )}

      <div>
        <div className="mb-1.5 flex items-baseline justify-between">
          <span className="text-[13px] font-medium">{full ? '고정 근무 요일·시간' : '근무 파트'}</span>
          <span className="text-[11px] text-muted">{full ? '요일을 눌러 근무·쉼 전환' : '요일·파트 이름을 누르면 한 줄 전체 전환'}</span>
        </div>
        {full ? (
          <div className="space-y-1.5">
            {DAY_LABELS.map((d, di) => {
              const w = wh[di];
              return (
                <div key={d} className="flex items-center gap-2 text-sm">
                  <button
                    onClick={() => setDay(di, w ? null : { ...prevTime(di) })}
                    aria-pressed={!!w}
                    aria-label={`${d}요일 근무`}
                    className={cx('h-8 w-9 shrink-0 rounded-md text-xs', w ? 'bg-staff-bg font-medium text-staff' : 'bg-brand-subtle text-muted hover:bg-line')}
                  >
                    {d}
                  </button>
                  {w ? (
                    <>
                      <input type="time" step={900} aria-label={`${d} 출근`} className={cx(inputBase, 'h-8 w-28')} value={w.start} onChange={(e) => setDay(di, { ...w, start: e.target.value })} />
                      –
                      <input type="time" step={900} aria-label={`${d} 퇴근`} className={cx(inputBase, 'h-8 w-28')} value={w.end} onChange={(e) => setDay(di, { ...w, end: e.target.value })} />
                      {w.start >= w.end && <span className="text-xs text-danger">시각 확인</span>}
                    </>
                  ) : (
                    <span className="text-xs text-muted">쉼</span>
                  )}
                </div>
              );
            })}
            {noDay && <p className="text-xs text-danger">근무 요일을 하나 이상 고르세요.</p>}
          </div>
        ) : !parts.length ? (
          <p className="text-xs text-muted">매장 설정에서 알바생 근무 파트를 먼저 추가하세요.</p>
        ) : (
          <div>
            {/* 폼 너비(420px) 안에 7일이 다 보이도록 요일 칸은 남은 너비를 나눠 쓴다 */}
            <div className="grid items-center gap-1" style={{ gridTemplateColumns: 'minmax(72px, max-content) repeat(7, minmax(0, 1fr))' }}>
              <span />
              {DAY_LABELS.map((d, di) => (
                <button key={d} onClick={() => toggleDay(di)} className="text-center text-xs text-muted hover:text-ink">
                  {d}
                </button>
              ))}
              {parts.map((p) => (
                <div key={p.id} className="contents">
                  <button onClick={() => togglePart(p.id)} className="pr-2 text-left text-xs leading-tight hover:underline">
                    <span className="font-medium">{p.name}</span>
                    <br />
                    <span className="text-[11px] text-muted">
                      {p.start}–{p.end}
                    </span>
                  </button>
                  {DAY_LABELS.map((d, di) => {
                    const on = s.availableParts[di]?.includes(p.id);
                    return (
                      <button
                        key={d}
                        onClick={() => toggle(di, p.id)}
                        aria-label={`${d} ${p.name}`}
                        aria-pressed={on}
                        className={cx('grid h-[30px] place-items-center rounded-md text-xs', on ? PART_TONE[p.tone] : 'bg-brand-subtle text-muted hover:bg-line')}
                      >
                        {on && <Icon name="check" size={14} />}
                      </button>
                    );
                  })}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {(save.error || remove.error) && <p className="text-sm text-danger">{(save.error ?? remove.error)!.message}</p>}
      <div className="flex gap-2 pt-1">
        {s.id > 0 && (
          <Button variant="danger" onClick={() => window.confirm(`${initial.name}님을 삭제할까요? 근무표의 배치도 함께 삭제됩니다.`) && remove.mutate()}>
            삭제
          </Button>
        )}
        <Button variant="primary" className="ml-auto" disabled={!s.name.trim() || badHours || save.isPending} onClick={() => save.mutate()}>
          저장
        </Button>
      </div>
    </Card>
  );
}
