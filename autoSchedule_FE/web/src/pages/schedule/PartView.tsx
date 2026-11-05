// 파트별 주간 보기: 알바생은 파트 칸으로 드래그, 직원은 유동 근무 줄에 시각으로 표시
import { DndContext, PointerSensor, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core';
import { cx } from '../../components/ui';
import { placeBreak, withTimes } from '../../lib/rules';
import { useEditor } from '../../store/editor';
import type { Schedule, Staff, Store } from '../../types';
import type { DialogTarget } from './ShiftDialog';
import { DayHeader, DraggableShift, DropCell, GRID_COLS, OutCell } from './WeekGrid';

interface Props {
  schedule: Schedule;
  staff: Staff[];
  store: Store;
  dates: (string | null)[];
  onOpen: (t: DialogTarget) => void;
}

const FREE = 'FREE';

export default function PartView({ schedule, staff, store, dates, onOpen }: Props) {
  const edit = useEditor((s) => s.edit);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const nameOf = (id: number) => staff.find((s) => s.id === id)?.name ?? '';

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over) return;
    const [partId, date] = String(over.id).split('|');
    const part = store.parts.find((p) => p.id === partId);
    edit((s) => {
      const i = s.shifts.findIndex((x) => x.id === active.id);
      const sh = { ...s.shifts[i], date };
      const isFull = staff.find((x) => x.id === sh.staffId)?.employmentType === 'FULL_TIME';
      // 직원은 파트에 묶지 않는다: 날짜만 옮긴다
      s.shifts[i] =
        part && !isFull
          ? withTimes({ ...sh, partId: part.id, breakStart: null }, part.start, part.end, part.breakMinutes)
          : placeBreak({ ...sh, partId: partId === FREE || isFull ? null : sh.partId });
    });
  };

  const rows = [
    ...store.parts.map((p) => ({ id: p.id, title: p.name, sub: `${p.start}–${p.end}`, tone: p.tone as string })),
    { id: FREE, title: '유동 근무', sub: '직원 · 시간 직접 지정', tone: 'staff' },
  ];

  return (
    <DndContext sensors={sensors} onDragEnd={onDragEnd}>
      <div className="min-w-[980px]">
        <DayHeader dates={dates} first="파트" />
        {rows.map((r) => (
          <div key={r.id} className={cx('grid border-b border-line', GRID_COLS)}>
            <div className="flex items-center gap-2.5 px-4 py-3">
              <span
                className={cx(
                  'size-3 shrink-0 rounded border',
                  r.tone === 'open' && 'border-open bg-open-bg',
                  r.tone === 'middle' && 'border-middle bg-middle-bg',
                  r.tone === 'close' && 'border-close bg-close-bg',
                  r.tone === 'staff' && 'border-staff bg-staff-bg',
                )}
              />
              <div>
                <div className="text-sm font-bold">{r.title}</div>
                <div className="text-[11px] text-muted">{r.sub}</div>
              </div>
            </div>
            {dates.map((d, i) => {
              if (!d) return <OutCell key={i} />;
              const shifts = schedule.shifts
                .filter((s) => s.date === d && (r.id === FREE ? !s.partId : s.partId === r.id))
                .sort((a, b) => a.start.localeCompare(b.start));
              return (
                <DropCell key={d} id={`${r.id}|${d}`} onEmptyClick={() => onOpen({ date: d, partId: r.id === FREE ? null : r.id })}>
                  {shifts.length > 0 &&
                    shifts.map((s) => (
                      <div key={s.id}>
                        <div className="mb-0.5 truncate px-0.5 text-[11px] font-medium">{nameOf(s.staffId)}</div>
                        <DraggableShift shift={s} store={store} onOpen={() => onOpen({ date: d, shift: s })} />
                      </div>
                    ))}
                </DropCell>
              );
            })}
          </div>
        ))}
      </div>
    </DndContext>
  );
}
