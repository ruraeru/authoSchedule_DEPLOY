import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Fragment, useEffect, useState } from 'react';
import { api } from '../api';
import { defaultStore } from '../api/defaults';
import { keys, useStaff, useStore } from '../api/queries';
import Icon from '../components/Icon';
import PageHeader from '../components/PageHeader';
import { Badge, Button, Card, cx, inputBase, Loading, Modal, Segmented } from '../components/ui';
import { partBreakFor, sizeOf, sortParts } from '../lib/rules';
import { fromMin, toMin } from '../lib/time';
import type { Part, PartTone, Store } from '../types';

const toneBar: Record<string, string> = {
  open: 'bg-open-bg border-open',
  middle: 'bg-middle-bg border-middle',
  close: 'bg-close-bg border-close',
  staff: 'bg-staff-bg border-staff',
};
// 15분 단위 24시간제. 기본 time 입력은 한국어 환경에서 "오전 08:00"으로 넓게 나와서 select를 쓴다
const TIMES = Array.from({ length: 24 * 4 + 1 }, (_, i) => fromMin(i * 15));

function TimeRange({ label, start, end, onChange }: { label: string; start: string; end: string; onChange: (start: string, end: string) => void }) {
  const sel = 'cursor-pointer appearance-none rounded-md bg-transparent px-1.5 py-1 outline-none hover:bg-bg focus-visible:bg-brand-subtle';
  return (
    <span className="inline-flex h-9 shrink-0 items-center rounded-[10px] border border-line-strong bg-surface px-1 text-[13px] tabular-nums">
      <select aria-label={`${label} 시작`} className={sel} value={start} onChange={(e) => onChange(e.target.value, end)}>
        {TIMES.map((t) => (
          <option key={t}>{t}</option>
        ))}
      </select>
      <span className="text-muted">–</span>
      <select aria-label={`${label} 종료`} className={sel} value={end} onChange={(e) => onChange(start, e.target.value)}>
        {TIMES.map((t) => (
          <option key={t}>{t}</option>
        ))}
      </select>
    </span>
  );
}
type SizeMode = Store['sizeMode'];
const SIZE_MODES: { value: SizeMode; label: string }[] = [
  { value: 'AUTO', label: '자동 판정 (권장)' },
  { value: 'OVER_5', label: '5인 이상 강제 적용' },
  { value: 'UNDER_5', label: '5인 미만 강제 적용' },
];
const sizeLabel = (size: Store['size']) => (size === 'OVER_5' ? '5인 이상' : '5인 미만');

// 새 파트 색은 세 가지를 돌려 쓴다
const TONES: PartTone[] = ['open', 'middle', 'close'];

const DAY = 24 * 60;
/** 익일 마감: 종료가 24시를 넘는다(26:00 = 익일 02:00) */
const nextDay = (t: string) => toMin(t) > DAY;

/** 저장 막는 이유. 없으면 null */
export function storeError(s: Store): string | null {
  if (toMin(s.open) >= toMin(s.close)) return '평일 영업 종료 시각은 시작 시각보다 늦어야 합니다.';
  if (toMin(s.weekendOpen) >= toMin(s.weekendClose)) return '주말·공휴일 영업 종료 시각은 시작 시각보다 늦어야 합니다.';
  if (toMin(s.close) - toMin(s.open) > DAY || toMin(s.weekendClose) - toMin(s.weekendOpen) > DAY) return '영업시간은 24시간을 넘을 수 없습니다.';
  for (const p of s.parts) {
    if (!p.name.trim()) return '파트 이름을 입력하세요.';
    if (toMin(p.start) >= toMin(p.end)) return `${p.name} 종료 시각은 시작 시각보다 늦어야 합니다.`;
  }
  return null;
}

export default function Settings() {
  const { data: store } = useStore();
  const { data: staff } = useStaff();
  const qc = useQueryClient();
  const [form, setForm] = useState<Store | null>(null);
  // 수동 규모로 바꾸기 전 확인 창에 띄울 모드
  const [pendingMode, setPendingMode] = useState<Store['size'] | null>(null);
  useEffect(() => setForm(store ?? null), [store]);

  const save = useMutation({
    mutationFn: (s: Store) => api.updateStore(s),
    onSuccess: (s) => {
      qc.setQueryData(keys.store, s);
      // 지운 파트는 구성원·근무표에서도 정리되므로 다시 불러온다
      qc.invalidateQueries({ queryKey: keys.staff });
      qc.invalidateQueries({ queryKey: keys.schedules });
      qc.invalidateQueries({ queryKey: keys.templates });
    },
  });

  if (!store || !form) return <Loading />;
  const set = (p: Partial<Store>) => setForm({ ...form, ...p });
  const setPart = (id: string, p: Partial<Part>) =>
    set({
      parts: form.parts.map((x) => {
        if (x.id !== id) return x;
        const next = { ...x, ...p };
        // 시간을 바꾸면 휴게를 4시간당 30분으로 다시 잡는다. 휴게만 고치면 그대로 둔다
        if (p.start === undefined && p.end === undefined) return next;
        return {
          ...next,
          breakMinutes: Math.max(0, partBreakFor(toMin(next.end) - toMin(next.start))),
        };
      }),
    });
  // 시작 시각이 가장 늦은 파트 뒤 시각으로 새 파트를 만든다
  const addPart = () => {
    const sorted = sortParts(form.parts);
    const last = sorted[sorted.length - 1];
    // 파트 시각은 24:00까지만 고를 수 있어 익일 마감이어도 자정에서 자른다
    const close = Math.min(toMin(form.close), DAY);
    const start = last && toMin(last.end) < close ? last.end : form.open;
    const end = fromMin(Math.min(toMin(start) + 4 * 60, close));
    const tone = TONES[form.parts.length % TONES.length];
    set({
      parts: [
        ...form.parts,
        {
          id: crypto.randomUUID(),
          name: '새 파트',
          tone,
          start,
          end,
          breakMinutes: partBreakFor(toMin(end) - toMin(start)),
          required: 1,
        },
      ],
    });
  };
  const removePart = (p: Part) => {
    if (store.parts.some((x) => x.id === p.id) && !window.confirm(`${p.name} 파트를 지울까요? 저장하면 이 파트로 잡힌 근무는 파트 없는 근무로 바뀌고, 구성원의 근무 가능 파트에서도 빠집니다.`)) return;
    set({ parts: form.parts.filter((x) => x.id !== p.id) });
  };
  const error = storeError(form);
  const dirty = JSON.stringify(form) !== JSON.stringify(store);

  return (
    <div className="space-y-5">
      <PageHeader
        title="매장 설정"
        sub={[store.name, store.branch].filter(Boolean).join(' ')}
        actions={
          <>
            <Button onClick={() => setForm(store)} disabled={!dirty || save.isPending}>
              되돌리기
            </Button>
            <Button
              variant="primary"
              onClick={() =>
                save.mutate({
                  ...form,
                  name: form.name.trim() || defaultStore.name,
                })
              }
              disabled={!dirty || !!error || save.isPending}
            >
              {save.isPending ? '저장 중…' : '저장'}
            </Button>
          </>
        }
      />
      {(error || save.error) && <p className="rounded-[10px] bg-danger-subtle px-4 py-3 text-sm text-danger">{error ?? save.error!.message}</p>}
      <Card className="min-w-0">
        <h2 className="border-b border-line px-6 py-4 font-bold">기본 정보</h2>
        <dl className="grid grid-cols-1 items-center gap-x-4 gap-y-3 px-6 py-5 text-sm sm:grid-cols-[200px_1fr]">
          <dt className="text-muted">매장 이름</dt>
          <dd className="mb-1 sm:mb-0">
            <input
              aria-label="매장 이름"
              className={cx(inputBase, 'h-9 w-64 max-w-full px-2.5 text-[13px]')}
              placeholder={defaultStore.name}
              value={form.name}
              onChange={(e) => set({ name: e.target.value })}
            />
          </dd>
          {(
            [
              ['평일', 'open', 'close'],
              ['주말·공휴일', 'weekendOpen', 'weekendClose'],
            ] as const
          ).map(([label, o, c]) => (
            <Fragment key={label}>
              <dt className="text-muted">영업시간 · {label}</dt>
              <dd className="mb-1 sm:mb-0">
                <span className="inline-flex items-center gap-2">
                  <TimeRange
                    label={`${label} 영업`}
                    start={form[o]}
                    end={nextDay(form[c]) ? fromMin(toMin(form[c]) - DAY) : form[c]}
                    onChange={(a, b) => set({ [o]: a, [c]: nextDay(form[c]) ? fromMin(toMin(b) + DAY) : b })}
                  />
                  <button
                    type="button"
                    aria-pressed={nextDay(form[c])}
                    aria-label={`${label} 영업 종료 익일`}
                    onClick={() =>
                      // 켜면 종료에 24시간을 더한다. 24:00(자정)이면 익일 01:00으로
                      set({ [c]: nextDay(form[c]) ? fromMin(toMin(form[c]) - DAY) : fromMin(Math.max((toMin(form[c]) % DAY) + DAY, DAY + 60)) })
                    }
                    className={cx(
                      'h-9 rounded-full border px-3 text-[13px]',
                      nextDay(form[c]) ? 'border-ink bg-brand-subtle font-medium text-ink' : 'border-line-strong text-muted hover:bg-bg',
                    )}
                  >
                    익일
                  </button>
                </span>
              </dd>
            </Fragment>
          ))}
          <dt className="self-start pt-1.5 text-muted">사업장 규모</dt>
          <dd className="mb-1 space-y-2 sm:mb-0">
            <Segmented value={form.sizeMode} options={SIZE_MODES} onChange={(m) => (m === 'AUTO' ? set({ sizeMode: m }) : setPendingMode(m))} />
            {(() => {
              const size = sizeOf(form.sizeMode, staff?.length ?? 0);
              return (
                <p className="flex flex-wrap items-center gap-2 text-xs text-muted">
                  <Badge tone="brand">
                    {form.sizeMode === 'AUTO' ? '자동 산정 중' : '수동 설정'}: {sizeLabel(size)} 사업장 적용
                  </Badge>
                  {form.sizeMode === 'AUTO' && `등록 구성원 ${staff?.length ?? 0}명 기준으로 자동 갱신`}
                  {size === 'OVER_5' ? ' · 연장·야간 가산, 주 52시간 적용' : ' · 연장·야간 가산 없음'}
                </p>
              );
            })()}
          </dd>
          <dt className="text-muted">최저시급</dt>
          <dd>{store.minimumWage.toLocaleString()}원 (2026년)</dd>
        </dl>
      </Card>
      <Card className="min-w-0">
        <div className="flex items-center justify-between gap-3 border-b border-line px-6 py-4">
          <div>
            <h2 className="font-bold">알바생 근무 파트</h2>
            <p className="text-xs text-muted">시작 시각 순으로 보여줍니다. 휴게시간은 근무 4시간마다 30분으로 자동 설정되며, 직접 고칠 수 있습니다.</p>
          </div>
          <Button size="sm" onClick={addPart} className="shrink-0">
            <Icon name="plus" size={14} />
            파트 추가
          </Button>
        </div>
        {form.parts.length > 0 && (
          // 파트 한 줄: 색 · 이름 · 근무 시간 · 휴게 · 필요 인원 · 삭제 를 같은 열에 맞춰 가로로
          <div className="grid grid-cols-[minmax(0,14rem)_auto_auto_auto_auto] gap-x-4 px-6 pt-3 pb-2 text-sm sm:grid-cols-[minmax(0,17.5rem)_auto_6rem_5rem_1fr]">
            <span className="pb-1.5 pl-5 text-xs text-muted">파트 이름</span>
            <span className="pb-1.5 pl-1 text-xs text-muted">근무 시간</span>
            <span className="pb-1.5 text-xs text-muted">휴게</span>
            <span className="pb-1.5 text-xs text-muted">필요 인원</span>
            <span />
            {sortParts(form.parts).map((p) => (
              <div key={p.id} className="contents">
                <span className="flex min-w-0 items-center gap-2 border-t border-line py-2">
                  <span className={cx('h-7 w-3 shrink-0 rounded-md border', toneBar[p.tone])} />
                  <input aria-label="파트 이름" className={cx(inputBase, 'h-9 w-full min-w-0 px-2.5 text-[13px]')} value={p.name} onChange={(e) => setPart(p.id, { name: e.target.value })} />
                </span>
                <span className="flex items-center border-t border-line py-2">
                  <TimeRange label={p.name} start={p.start} end={p.end} onChange={(start, end) => setPart(p.id, { start, end })} />
                </span>
                <span className="flex items-center border-t border-line py-2">
                  <select
                    aria-label={`${p.name} 휴게`}
                    className={cx(inputBase, 'h-9 cursor-pointer px-2 text-[13px]')}
                    value={p.breakMinutes}
                    onChange={(e) =>
                      setPart(p.id, {
                        breakMinutes: Number(e.target.value),
                      })
                    }
                  >
                    {[0, 30, 60, 90, 120].map((m) => (
                      <option key={m} value={m}>
                        {m}분
                      </option>
                    ))}
                  </select>
                </span>
                <span className="flex items-center gap-1 border-t border-line py-2 text-muted">
                  <input
                    type="number"
                    min={0}
                    aria-label={`${p.name} 필요 인원`}
                    className={cx(inputBase, 'h-9 w-14 px-2 text-[13px] tabular-nums')}
                    value={p.required}
                    onChange={(e) =>
                      setPart(p.id, {
                        required: Math.max(0, Math.floor(Number(e.target.value) || 0)),
                      })
                    }
                  />
                  명
                </span>
                <span className="flex items-center justify-end border-t border-line py-2">
                  <Button size="sm" variant="ghost" onClick={() => removePart(p)} aria-label={`${p.name} 삭제`}>
                    삭제
                  </Button>
                </span>
              </div>
            ))}
          </div>
        )}
      </Card>
      {pendingMode && (
        <Modal
          title="사업장 규모를 직접 정할까요?"
          onClose={() => setPendingMode(null)}
          footer={
            <>
              <Button onClick={() => setPendingMode(null)}>취소</Button>
              <Button
                variant="primary"
                onClick={() => {
                  set({ sizeMode: pendingMode });
                  setPendingMode(null);
                }}
              >
                {sizeLabel(pendingMode)}으로 적용
              </Button>
            </>
          }
        >
          <p className="text-sm">
            <b>{sizeLabel(pendingMode)}</b> 사업장으로 고정합니다. 연장·야간 가산수당 및 주 52시간 제한 규칙이 변경됩니다.
          </p>
          <p className="text-xs text-muted">구성원 수가 바뀌어도 자동으로 갱신되지 않습니다. 저장해야 반영됩니다.</p>
        </Modal>
      )}
    </div>
  );
}
