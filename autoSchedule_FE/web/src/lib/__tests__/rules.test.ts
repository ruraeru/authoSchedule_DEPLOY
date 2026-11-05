import { describe, expect, it } from 'vitest';
import { SEED_WEEK, seedStaff, seedStore, seedWeek as seedSchedule } from './fixtures';
import type { Part, Shift } from '../../types';
import { upgrade } from '../../api/mock';
import { canWork, fixedShifts, gaps, hoursOn, minBreakFor, partBreakFor, requiredAt, requiredBreak, sizeOf, sortByPart, sortParts, sortShifts, validate, withTimes } from '../rules';
import { storeError } from '../../pages/Settings';
import { datesBetween, dayIndex, monthEnd, weekDates, weeksOf } from '../time';
import { autoFill } from '../autofill';

const dates = weekDates(seedSchedule.periodStart);
const FRI = '2026-11-06';

describe('휴게시간 (R-04)', () => {
  it('근로 4시간 이상 30분, 8시간 이상 1시간', () => {
    expect(requiredBreak(239)).toBe(0);
    expect(requiredBreak(240)).toBe(30);
    expect(requiredBreak(480)).toBe(60);
  });
  it('체류 시간 기준 최소 휴게', () => {
    expect(minBreakFor(210)).toBe(0); // 3.5h
    expect(minBreakFor(240)).toBe(30); // 4h → 3.5h 근로
    expect(minBreakFor(540)).toBe(60); // 9h → 8h 근로
  });
  it('출퇴근을 바꾸면 휴게를 다시 맞춘다', () => {
    const s: Shift = { id: 'x', staffId: 1, date: FRI, start: '09:00', end: '12:00', partId: null, breakStart: null, breakMinutes: 0, tentative: false };
    const next = withTimes(s, '09:00', '18:00');
    expect(next.breakMinutes).toBe(60);
    expect(next.breakStart).toBe('13:00');
  });
  it('사업장 규모: 자동은 구성원 5명부터 5인 이상, 수동은 그대로', () => {
    expect([sizeOf('AUTO', 4), sizeOf('AUTO', 5), sizeOf('UNDER_5', 9), sizeOf('OVER_5', 0)]).toEqual(['UNDER_5', 'OVER_5', 'UNDER_5', 'OVER_5']);
  });
  it('파트 휴게 기본값은 4시간당 30분', () => {
    expect([180, 240, 300, 480, 600].map(partBreakFor)).toEqual([0, 30, 30, 60, 60]);
  });
});

describe('예시 근무표 검증', () => {
  const v = validate(seedSchedule, seedStaff, seedStore, dates);

  it('위반은 박하은 토요일 가능 시간 외 배치 1건', () => {
    const errors = v.issues.filter((i) => i.severity === 'error');
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ ruleId: 'R-01', staffId: 5, date: '2026-11-07' });
  });

  it('서하린 토요일 시간 미정 → 확정 불가', () => {
    expect(v.tentativeCount).toBe(1);
    expect(v.canConfirm).toBe(false);
  });

  it('직원 주간 집계: 확정·미정·연차를 나눠 센다', () => {
    expect(v.weeks.get(dates[0])!.get(1)).toMatchObject({ workMin: 40 * 60, tentativeMin: 0, overtimeMin: 0 });
    expect(v.weeks.get(dates[0])!.get(2)).toMatchObject({ workMin: 24 * 60, tentativeMin: 8 * 60, leaveMin: 8 * 60 });
  });

  it('커버리지 부족: 오픈 혼자인 목·일 휴게 구간', () => {
    const all = dates.flatMap((d) => gaps(v.coverageByDate[d]).map((g) => ({ d, ...g })));
    expect(all).toEqual([
      { d: '2026-11-05', start: 10 * 60, end: 10 * 60 + 30, shortage: 1 },
      { d: '2026-11-08', start: 10 * 60, end: 10 * 60 + 30, shortage: 1 },
    ]);
  });
});

describe('연소자 (R-05, R-06)', () => {
  it('1일 7시간 초과와 22시 이후 근무를 막는다', () => {
    const minor: Shift = { id: 'm', staffId: 10, date: FRI, start: '13:00', end: '22:30', partId: null, breakStart: '17:00', breakMinutes: 60, tentative: false };
    const v = validate({ ...seedSchedule, shifts: [minor] }, seedStaff, seedStore, dates);
    const ids = v.issues.filter((i) => i.staffId === 10 && i.severity === 'error').map((i) => i.ruleId);
    expect(ids).toEqual(expect.arrayContaining(['R-05', 'R-06']));
  });
});

describe('한 달 기간', () => {
  it('말일과 월~일 주 나누기: 달 경계 밖 요일은 비운다', () => {
    expect(monthEnd('2026-02-01')).toBe('2026-02-28');
    const weeks = weeksOf(datesBetween('2026-11-01', monthEnd('2026-11-01')));
    expect(weeks).toHaveLength(6);
    expect(weeks[0]).toEqual([null, null, null, null, null, null, '2026-11-01']);
    expect(weeks[5][0]).toBe('2026-11-30');
    expect(weeks[5].slice(1).every((d) => d === null)).toBe(true);
  });

  it('주간 집계는 주마다 따로 한다', () => {
    const month = datesBetween('2026-11-01', '2026-11-30');
    const v = validate(seedSchedule, seedStaff, seedStore, month);
    expect(v.weeks.size).toBe(6);
    expect(v.weeks.get('2026-11-02')!.get(1)!.workMin).toBe(40 * 60);
    expect(v.weeks.get('2026-11-09')!.get(1)!.workMin).toBe(0);
  });
});

describe('매장 설정 검증', () => {
  it('영업시간·파트 시간이 어긋나면 저장을 막는다', () => {
    expect(storeError(seedStore)).toBeNull();
    expect(storeError({ ...seedStore, open: '22:00', close: '08:00' })).toMatch('평일 영업 종료');
    expect(storeError({ ...seedStore, weekendOpen: '23:00' })).toMatch('주말·공휴일');
    // 익일 마감(26:00 = 익일 02:00)은 통과, 24시간 넘으면 막는다
    expect(storeError({ ...seedStore, open: '18:00', close: '26:00', weekendClose: '27:00' })).toBeNull();
    expect(storeError({ ...seedStore, open: '08:00', close: '33:00' })).toMatch('24시간');
    const late = { ...seedStore.parts[2], end: '23:00' };
    expect(storeError({ ...seedStore, parts: [late] })).toBeNull();
    expect(storeError({ ...seedStore, parts: [{ ...late, name: ' ' }] })).toMatch('이름');
  });
});

describe('영업시간 평일 / 주말·공휴일', () => {
  it('토·일과 공휴일은 주말 영업시간을 쓴다', () => {
    const store = { ...seedStore, weekendOpen: '10:00', weekendClose: '20:00' };
    expect(hoursOn(store, '2026-11-06')).toEqual({ open: '08:00', close: '22:00' }); // 금
    expect(hoursOn(store, '2026-11-07')).toEqual({ open: '10:00', close: '20:00' }); // 토
    expect(hoursOn(store, '2026-10-09')).toEqual({ open: '10:00', close: '20:00' }); // 한글날(금)
  });
});

describe('근무 가능 파트 (R-01)', () => {
  const park = seedStaff.find((s) => s.id === 5)!; // 토요일 오픈·미들만 가능
  const sat = '2026-11-07';
  it('알바생은 그 요일에 고른 파트만, 직접 입력은 고른 파트 시간 안만 가능', () => {
    expect(canWork(park, seedStore, { date: sat, start: '17:00', end: '22:00', partId: 'CLOSE' })).toBe(false);
    expect(canWork(park, seedStore, { date: sat, start: '12:00', end: '17:00', partId: 'MIDDLE' })).toBe(true);
    expect(canWork(park, seedStore, { date: sat, start: '10:00', end: '16:00', partId: null })).toBe(true);
    expect(canWork(park, seedStore, { date: sat, start: '16:00', end: '18:00', partId: null })).toBe(false);
  });
  it('직원은 언제나 가능', () => {
    expect(canWork(seedStaff[0], seedStore, { date: sat, start: '06:00', end: '23:00', partId: null })).toBe(true);
  });
});

describe('fixedShifts', () => {
  it('필요 인원은 그 시각을 덮는 파트들의 합', () => {
    const extra = { ...seedStore.parts[1], id: 'PEAK', start: '12:00', end: '14:00', required: 2 };
    const store = { ...seedStore, parts: [...seedStore.parts, extra] };
    expect([requiredAt(store, 11 * 60), requiredAt(store, 13 * 60), requiredAt(store, 15 * 60)]).toEqual([1, 3, 1]);
  });

  it('직원 고정 근무를 요일별로 깔고 쉬는 날은 뺀다', () => {
    // 월 10–19, 수 13–22, 나머지 쉼
    const workHours = [{ start: '10:00', end: '19:00' }, null, { start: '13:00', end: '22:00' }, null, null, null, null];
    const staff = seedStaff.map((s) => (s.id === 1 ? { ...s, workHours } : s));
    let n = 0;
    const out = fixedShifts(seedStore, staff, weekDates(SEED_WEEK), () => `t${++n}`).filter((s) => s.staffId === 1);
    expect(out.map((s) => [s.staffId, s.date, s.start, s.end])).toEqual([
      [1, '2026-11-02', '10:00', '19:00'],
      [1, '2026-11-04', '13:00', '22:00'],
    ]);
    expect(out[0].breakStart).not.toBeNull();
  });
});

describe('autoFill', () => {
  const dates = weekDates(SEED_WEEK);
  let n = 0;
  const out = autoFill(seedStore, seedStaff, dates, [], [], () => `a${++n}`);
  const byStaff = (id: number) => out.filter((s) => s.staffId === id);

  it('알바생은 그 요일에 고른 파트마다 빠짐없이 넣는다', () => {
    for (const st of seedStaff.filter((s) => s.employmentType === 'PART_TIME'))
      for (const date of dates) {
        const want = seedStore.parts.filter((p) => st.availableParts[dayIndex(date)].includes(p.id)).map((p) => [p.id, p.start, p.end]);
        expect(byStaff(st.id).filter((s) => s.date === date).map((s) => [s.partId, s.start, s.end])).toEqual(want);
      }
  });

  it('있는 근무는 그대로 두고, 연차인 날은 넣지 않는다', () => {
    const kept = seedSchedule.shifts.slice(0, 3);
    const leave = { id: 'l', staffId: 3, date: dates[1], type: 'ANNUAL' as const };
    const res = autoFill(seedStore, seedStaff, dates, kept, [leave], () => `b${++n}`);
    expect(res).toEqual(expect.arrayContaining(kept));
    expect(res).toEqual(sortShifts(res));
    expect(res.some((s) => s.staffId === 3 && s.date === dates[1])).toBe(false);
  });

  it('같은 파트 근무가 이미 있으면 또 넣지 않는다', () => {
    const again = autoFill(seedStore, seedStaff, dates, out, [], () => `c${++n}`);
    expect(again).toHaveLength(out.length);
  });
});

describe('파트 정렬', () => {
  it('시작 시각 순, 같으면 끝나는 시각 순', () => {
    const mk = (id: string, start: string, end: string) => ({ ...seedStore.parts[0], id, start, end });
    expect(sortParts([mk('a', '15:00', '19:00'), mk('b', '12:00', '17:00'), mk('c', '08:00', '14:00'), mk('d', '08:00', '12:00')]).map((p) => p.id)).toEqual(['d', 'c', 'b', 'a']);
  });

  it('알바생은 가장 이른 파트 → 첫 요일 → 등록 순, 파트 없으면 맨 뒤', () => {
    const [early, late] = sortParts(seedStore.parts);
    const mk = (id: number, days: Record<number, string>) => ({ ...seedStaff[2], id, availableParts: Array.from({ length: 7 }, (_, i) => (days[i] ? [days[i]] : [])) });
    const staff = [mk(1, {}), mk(2, { 0: late.id }), mk(3, { 2: early.id }), mk(4, { 0: early.id, 1: late.id }), mk(5, { 2: early.id })];
    expect(sortByPart(staff, [...seedStore.parts].reverse()).map((s) => s.id)).toEqual([4, 3, 5, 2, 1]);
  });

  it('고정 근무는 파트 순서와 상관없이 날짜·시작 시각 순으로 나온다', () => {
    const store = { ...seedStore, parts: [...seedStore.parts].reverse() };
    let n = 0;
    const out = fixedShifts(store, [...seedStaff].reverse(), weekDates(SEED_WEEK), () => `s${++n}`);
    expect(out.length).toBeGreaterThan(1);
    expect(out).toEqual(sortShifts(out));
    expect(out.map((s) => `${s.date} ${s.start}`)).toEqual(out.map((s) => `${s.date} ${s.start}`).sort());
  });

  it('예전 주말 복사본(id_WE)은 원래 파트로 합친다', () => {
    const x = { ...seedStore.parts[0], id: 'X', days: 'WEEKDAY' } as Part;
    const st = { ...seedStaff[2], availableParts: Array.from({ length: 7 }, (_, i) => (i >= 5 ? ['X_WE'] : ['X'])) };
    const shift = (date: string, partId: string): Shift => ({ id: date, staffId: st.id, date, start: '08:00', end: '12:00', partId, breakStart: null, breakMinutes: 0, tentative: false });
    const sc = { ...seedSchedule, shifts: [shift('2026-11-06', 'X'), shift('2026-11-07', 'X_WE')] };
    const d = upgrade({ store: { ...seedStore, parts: [x, { ...x, id: 'X_WE', days: 'WEEKEND' } as Part] }, staff: [st], schedules: [sc], templates: [] });
    expect(d.store.parts).toEqual([{ ...seedStore.parts[0], id: 'X' }]);
    expect(d.staff[0].availableParts.flat()).toEqual(Array(7).fill('X'));
    expect(d.schedules[0].shifts.map((s) => s.partId)).toEqual(['X', 'X']);
  });
});
