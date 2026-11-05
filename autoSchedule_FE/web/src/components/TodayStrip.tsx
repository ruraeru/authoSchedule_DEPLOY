// 오늘 06~24시: 파트별 줄에 사람마다 막대. 지금 시각은 진한 세로선 (Figma DS TodayStrip)
import { cx } from './ui';
import { fromMin, toMin } from '../lib/time';
import type { Shift, Staff, Store } from '../types';

const FROM = 6 * 60;
const TO = 24 * 60;
const pct = (m: number) => `${((Math.min(Math.max(m, FROM), TO) - FROM) / (TO - FROM)) * 100}%`;
const TONE: Record<string, string> = { open: 'bg-open-bg text-open', middle: 'bg-middle-bg text-middle', close: 'bg-close-bg text-close', staff: 'bg-staff-bg text-staff' };
const short = (t: string) => (t.endsWith(':00') ? String(Number(t.slice(0, 2))).padStart(2, '0') : t);

export default function TodayStrip({ shifts, staff, store, now }: { shifts: Shift[]; staff: Staff[]; store: Store; now?: number }) {
  const name = (id: number) => staff.find((s) => s.id === id)?.name ?? '?';
  const lanes = [
    ...store.parts.map((p) => ({ key: p.id, label: p.name, tone: p.tone as string, items: shifts.filter((s) => s.partId === p.id) })),
    { key: 'staff', label: '직원', tone: 'staff', items: shifts.filter((s) => !s.partId) },
  ].filter((l) => l.items.length);
  const ticks = [6, 9, 12, 15, 18, 21, 24];

  if (!lanes.length) return <p className="py-6 text-sm text-muted">이 날은 근무가 없습니다.</p>;
  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[640px] grid-cols-[72px_1fr] gap-x-3">
        <span />
        <div className="relative h-5 text-xs text-muted tabular-nums">
          {ticks.map((h) => (
            <span key={h} className={cx('absolute', h === 24 && '-translate-x-full', h > 6 && h < 24 && '-translate-x-1/2')} style={{ left: pct(h * 60) }}>
              {String(h).padStart(2, '0')}
            </span>
          ))}
        </div>
        {lanes.map((l) => (
          <div key={l.key} className="contents">
            <span className="pt-2 text-xs font-medium text-muted">{l.label}</span>
            <div className="relative mt-1.5 space-y-1">
              {ticks.map((h) => (
                <span key={h} aria-hidden className="absolute inset-y-0 w-px bg-line" style={{ left: pct(h * 60) }} />
              ))}
              {l.items
                .slice()
                .sort((a, b) => a.start.localeCompare(b.start))
                .map((s) => (
                  <div key={s.id} className="relative h-[30px]">
                    <div
                      className={cx('absolute inset-y-0 flex items-center gap-1.5 overflow-hidden rounded-md px-2.5 text-xs whitespace-nowrap', TONE[l.tone] ?? TONE.staff, s.tentative && 'border-[1.5px] border-dashed border-staff bg-surface')}
                      style={{ left: pct(toMin(s.start)), right: `calc(100% - ${pct(toMin(s.end))})` }}
                      title={`${name(s.staffId)} ${s.start}–${s.end}`}
                    >
                      <span className="font-medium">{name(s.staffId)}</span>
                      <span className="tabular-nums">
                        {short(s.start)}–{short(s.end)}
                      </span>
                    </div>
                  </div>
                ))}
            </div>
          </div>
        ))}
        {now !== undefined && now >= FROM && now <= TO && (
          <div className="pointer-events-none relative col-start-2 row-start-1 row-end-[-1]">
            <span className="absolute inset-y-0 w-[1.5px] bg-ink" style={{ left: pct(now) }} />
            <span className="absolute -top-0.5 -translate-x-1/2 bg-surface px-1 text-xs font-medium tabular-nums" style={{ left: pct(now) }}>
              {fromMin(now)}
            </span>
          </div>
        )}
      </div>
    </div>
  );
}
