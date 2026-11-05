import { workMin } from '../lib/rules';
import { hours, shortRange } from '../lib/time';
import type { Shift, Store } from '../types';
import type { BlockKind } from './ui';

export function blockKind(shift: Shift, store: Store): BlockKind {
  if (shift.tentative) return 'tentative';
  if (!shift.partId) return 'staff';
  return store.parts.find((p) => p.id === shift.partId)?.tone ?? 'staff';
}

/** 블록 제목/부제. 직원은 시각, 알바생은 파트명 */
export function blockText(shift: Shift, store: Store) {
  if (shift.tentative) return { title: '시간 미정', sub: `AI 추천 ${shortRange(shift.start, shift.end)}` };
  const part = store.parts.find((p) => p.id === shift.partId);
  if (part) return { title: part.name, sub: `${shift.start}–${shift.end}` };
  return { title: `${shift.start}–${shift.end}`, sub: `${hours(workMin(shift))} · 휴게 ${shift.breakMinutes >= 60 ? `${shift.breakMinutes / 60}h` : `${shift.breakMinutes}m`}` };
}
