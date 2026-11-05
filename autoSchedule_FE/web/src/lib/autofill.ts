// AI 없이 규칙만으로 근무표 채우기
import type { Leave, Shift, Staff, Store } from '../types';
import { fixedShifts, sortShifts } from './rules';

/**
 * 이미 있는 근무는 그대로 두고 고정 근무를 넣는다.
 * 직원은 근무 없는 날에 요일별 고정 시각으로, 알바생은 그 요일에 고른 파트마다 넣는다(같은 파트 근무가 있으면 건너뜀).
 * 연차인 날은 넣지 않는다.
 */
export function autoFill(store: Store, staff: Staff[], dates: string[], existing: Shift[], leaves: Leave[], uid: () => string): Shift[] {
  const off = (f: Shift) => leaves.some((l) => l.staffId === f.staffId && l.date === f.date);
  const has = (f: Shift) => existing.some((s) => s.staffId === f.staffId && s.date === f.date && (!f.partId || s.partId === f.partId));
  return sortShifts([...existing, ...fixedShifts(store, staff, dates, uid).filter((f) => !off(f) && !has(f))]);
}
