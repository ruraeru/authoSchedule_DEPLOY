// 인원별 주간 보기: 직원(유동 근무)과 알바생(파트 근무)을 한 표에
import { DndContext, PointerSensor, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core';
import { Avatar, Badge, cx, ShiftBlock } from '../../components/ui';
import { sortByPart, type StaffWeek, type Validation } from '../../lib/rules';
import { hours } from '../../lib/time';
import { useEditor } from '../../store/editor';
import type { Schedule, Staff, Store } from '../../types';
import type { DialogTarget } from './ShiftDialog';
import { DayHeader, DraggableShift, DropCell, GRID_COLS, GroupHeader, OutCell } from './WeekGrid';

interface Props {
  schedule: Schedule;
  staff: Staff[];
  store: Store;
  v: Validation;
  dates: (string | null)[];
  onOpen: (t: DialogTarget) => void;
}

export default function PersonView({ schedule, staff, store, v, dates, onOpen }: Props) {
  const edit = useEditor((s) => s.edit);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const fullTime = staff.filter((s) => s.employmentType === 'FULL_TIME');
  const partTime = sortByPart(staff.filter((s) => s.employmentType === 'PART_TIME'), store.parts);

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over) return;
    const [staffId, date] = String(over.id).split('|');
    edit((s) => {
      const sh = s.shifts.find((x) => x.id === active.id)!;
      const target = staff.find((x) => x.id === Number(staffId))!;
      sh.staffId = target.id;
      sh.date = date;
      if (target.employmentType === 'FULL_TIME') sh.partId = null;
      else sh.tentative = false;
    });
  };

  const row = (p: Staff) => {
    const w = v.weeks.get(dates.find(Boolean)!)?.get(p.id);
    const isFull = p.employmentType === 'FULL_TIME';
    return (
      <div key={p.id} className={cx('grid border-b border-line', GRID_COLS)}>
        <div className="flex items-center gap-2.5 px-4 py-2">
          <Avatar name={p.name} id={p.id} />
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <span className="text-sm font-bold">{p.name}</span>
              <Badge tone={isFull ? 'staff' : p.isMinor ? 'warning' : 'brand'}>{p.title}</Badge>
            </div>
            {w && <div className="mt-0.5 truncate text-[11px] text-muted">{summary(p, w)}</div>}
          </div>
        </div>
        {dates.map((d, i) => {
          if (!d) return <OutCell key={i} />;
          const shifts = schedule.shifts.filter((s) => s.staffId === p.id && s.date === d).sort((a, b) => a.start.localeCompare(b.start));
          const leave = schedule.leaves.some((l) => l.staffId === p.id && l.date === d);
          return (
            <DropCell key={d} id={`${p.id}|${d}`} empty={isFull ? '휴무' : ''} onEmptyClick={() => onOpen({ date: d, staffId: p.id, partId: isFull ? null : store.parts[0]?.id })}>
              {(shifts.length || leave) && (
                <>
                  {leave && <ShiftBlock kind="leave" title="연차" sub="8h 유급" onClick={() => onOpen({ date: d, staffId: p.id })} />}
                  {shifts.map((s) => (
                    <DraggableShift key={s.id} shift={s} store={store} onOpen={() => onOpen({ date: d, shift: s })} />
                  ))}
                </>
              )}
            </DropCell>
          );
        })}
      </div>
    );
  };

  const partSummary = store.parts.map((p) => `${p.name} ${p.start}–${p.end}`).join(' · ');
  return (
    <DndContext sensors={sensors} onDragEnd={onDragEnd}>
      <div className="min-w-[980px]">
        <DayHeader dates={dates} first="구성원 · 주간 합계" />
        <GroupHeader title="직원 · 유동 근무" sub="출근 시간이 날마다 달라요 · 15분 단위로 시작/종료 조정 · 주 40시간 기준" />
        {fullTime.map(row)}
        <GroupHeader title="알바생 · 파트 근무" sub={partSummary} />
        {partTime.map(row)}
      </div>
    </DndContext>
  );
}

function summary(p: Staff, w: StaffWeek) {
  if (p.employmentType === 'FULL_TIME') {
    if (w.tentativeMin || w.leaveMin)
      return [`확정 ${hours(w.workMin)}`, w.tentativeMin && `미정 ${hours(w.tentativeMin)}`, w.leaveMin && `연차 ${hours(w.leaveMin)}`].filter(Boolean).join(' · ');
    return `${hours(w.workMin)} · 연장 ${hours(w.overtimeMin)}`;
  }
  return (
    <>
      {hours(w.workMin)}
      {w.weeklyHoliday && <span className="text-warning"> · 주휴수당 대상</span>}
    </>
  );
}
