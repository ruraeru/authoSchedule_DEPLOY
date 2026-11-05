// 한 달 한눈에: 날짜 칸마다 파트별 근무자 이름만. 칸을 누르면 그 날 일간 화면으로
import { cx } from '../../components/ui';
import { DAY_LABELS, isWeekendOrHoliday, weeksOf, datesBetween } from '../../lib/time';
import type { Schedule, Staff, Store } from '../../types';

const TONE: Record<string, string> = {
  open: 'bg-open-bg text-open',
  middle: 'bg-middle-bg text-middle',
  close: 'bg-close-bg text-close',
  staff: 'bg-staff-bg text-staff',
};

export default function MonthView({ schedule, staff, store, onDate }: { schedule: Schedule; staff: Staff[]; store: Store; onDate: (date: string) => void }) {
  const weeks = weeksOf(datesBetween(schedule.periodStart, schedule.periodEnd));
  const name = (id: number) => staff.find((s) => s.id === id)?.name ?? '?';
  // 행: 파트 순서대로, 마지막에 직원(파트 없는 근무)
  const rows = [...store.parts.map((p) => ({ id: p.id as string | null, label: p.name, tone: p.tone as string })), { id: null, label: '직원', tone: 'staff' }];

  return (
    <div className="min-w-[840px]">
      <div className="grid grid-cols-7 border-b border-line text-center text-xs text-muted">
        {DAY_LABELS.map((d) => (
          <div key={d} className="py-2">
            {d}
          </div>
        ))}
      </div>
      {weeks.map((w, wi) => (
        <div key={wi} className="grid grid-cols-7 border-b border-line last:border-b-0">
          {w.map((date, di) => {
            if (!date) return <div key={di} className="border-r border-line bg-bg/40 last:border-r-0" />;
            const shifts = schedule.shifts.filter((s) => s.date === date);
            const leaves = schedule.leaves.filter((l) => l.date === date);
            return (
              <button
                key={date}
                onClick={() => onDate(date)}
                className="flex min-h-28 flex-col gap-1 border-r border-line p-1.5 text-left align-top last:border-r-0 hover:bg-bg/60"
              >
                <span className={cx('text-xs font-bold', isWeekendOrHoliday(date) && 'text-danger')}>{Number(date.slice(8))}</span>
                {rows.map((r) => {
                  const here = shifts.filter((s) => s.partId === r.id);
                  if (!here.length) return null;
                  return (
                    <span key={r.label} className={cx('rounded px-1 py-0.5 text-[11px] leading-snug', TONE[r.tone])}>
                      <b className="font-semibold">{r.label}</b>{' '}
                      {here.map((s) => (
                        <span key={s.id} className={cx(s.tentative && 'underline decoration-dashed')}>
                          {name(s.staffId)}
                          {r.id === null && <span className="opacity-70"> {s.start.replace(/:00$/, '')}–{s.end.replace(/:00$/, '')}</span>}{' '}
                        </span>
                      ))}
                    </span>
                  );
                })}
                {leaves.length > 0 && <span className="px-1 text-[11px] text-muted">연차 {leaves.map((l) => name(l.staffId)).join(' ')}</span>}
              </button>
            );
          })}
        </div>
      ))}
    </div>
  );
}
