// 주간 그리드 공통 부품: 드래그 블록, 드롭 칸
import { useDraggable, useDroppable } from '@dnd-kit/core';
import type { ReactNode } from 'react';
import { blockKind, blockText } from '../../components/shiftDisplay';
import { cx, ShiftBlock } from '../../components/ui';
import { DAY_LABELS, md } from '../../lib/time';
import type { Shift, Store } from '../../types';

export const GRID_COLS = 'grid-cols-[192px_repeat(7,minmax(112px,1fr))]';

/** 달 경계 밖 요일 칸 */
export const OutCell = () => <div className="border-l border-line bg-bg/60" />;

export function DayHeader({ dates, first }: { dates: (string | null)[]; first: string }) {
  return (
    <div className={cx('grid border-b border-line text-sm', GRID_COLS)}>
      <div className="px-4 py-3 text-xs text-muted">{first}</div>
      {dates.map((d, i) => (
        <div key={i} className={cx('py-3 text-center font-bold', i >= 5 && 'text-muted', !d && 'text-muted/50')}>
          {DAY_LABELS[i]} {d && md(d)}
        </div>
      ))}
    </div>
  );
}

export function GroupHeader({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="border-b border-line px-4 py-2 text-xs">
      <b className="text-[13px]">{title}</b>
      <span className="ml-2 text-muted">{sub}</span>
    </div>
  );
}

export function DropCell({ id, children, onEmptyClick, empty }: { id: string; children?: ReactNode; onEmptyClick?: () => void; empty?: ReactNode }) {
  const { setNodeRef, isOver } = useDroppable({ id });
  return (
    <div
      ref={setNodeRef}
      className={cx('group relative min-h-14 space-y-1 border-l border-line p-1.5', isOver && 'bg-brand-subtle')}
      onClick={(e) => e.target === e.currentTarget && onEmptyClick?.()}
    >
      {children}
      {!children && (
        <button
          onClick={onEmptyClick}
          className="flex h-full min-h-11 w-full items-center justify-center rounded-md text-xs text-muted group-hover:bg-bg"
        >
          <span className="group-hover:hidden">{empty}</span>
          <span className="hidden text-base group-hover:inline">+</span>
        </button>
      )}
    </div>
  );
}

export function DraggableShift({ shift, store, onOpen }: { shift: Shift; store: Store; onOpen: () => void }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({ id: shift.id });
  const { title, sub } = blockText(shift, store);
  return (
    <div
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      style={transform ? { transform: `translate(${transform.x}px, ${transform.y}px)` } : undefined}
      className={cx('touch-none', isDragging && 'relative z-20 opacity-80 shadow-lg')}
    >
      <ShiftBlock kind={blockKind(shift, store)} title={title} sub={sub} onClick={onOpen} />
    </div>
  );
}
