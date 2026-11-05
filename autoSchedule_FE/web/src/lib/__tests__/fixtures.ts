// 테스트용 예시 매장 데이터 (Figma 05·06 화면과 같은 구성). 앱에는 들어가지 않는다.
import { placeBreak, minBreakFor } from '../rules';
import { addDays, toMin } from '../time';
import type { Leave, Part, Schedule, Shift, Staff, Store } from '../../types';

export const SEED_WEEK = '2026-11-02';

const parts: Part[] = [
  { id: 'OPEN', name: '오픈', tone: 'open', start: '08:00', end: '12:00', breakMinutes: minBreakFor(240), required: 1 },
  { id: 'MIDDLE', name: '미들', tone: 'middle', start: '12:00', end: '17:00', breakMinutes: minBreakFor(300), required: 1 },
  { id: 'CLOSE', name: '마감', tone: 'close', start: '17:00', end: '22:00', breakMinutes: minBreakFor(300), required: 1 },
];

export const seedStore: Store = {
  id: 1,
  name: '카페 커푸치노',
  branch: '성수점',
  sizeMode: 'OVER_5',
  size: 'OVER_5',
  open: '08:00',
  close: '22:00',
  weekendOpen: '08:00',
  weekendClose: '22:00',
  parts,
  minimumWage: 10320,
};

const ALL = ['OPEN', 'MIDDLE', 'CLOSE'];
const everyDay = (ids = ALL) => Array.from({ length: 7 }, () => [...ids]);

const person = (p: Partial<Staff> & Pick<Staff, 'id' | 'name'>): Staff => ({
  employmentType: 'PART_TIME',
  title: '알바',
  hourlyWage: 10320,
  isMinor: false,
  availableParts: everyDay(),
  phone: '010-0000-0000',
  ...p,
});

const parkAvailability = everyDay();
parkAvailability[5] = ['OPEN', 'MIDDLE']; // 박하은: 토요일 마감 불가

export const seedStaff: Staff[] = [
  person({ id: 1, name: '오지훈', employmentType: 'FULL_TIME', title: '매니저', hourlyWage: 13000, availableParts: everyDay([]), phone: '010-2481-3301' }),
  person({ id: 2, name: '서하린', employmentType: 'FULL_TIME', title: '정직원', hourlyWage: 11500, availableParts: everyDay([]), phone: '010-5512-0920' }),
  person({ id: 3, name: '김민지', phone: '010-3392-1187' }),
  person({ id: 4, name: '이서준', phone: '010-7720-4410' }),
  person({ id: 5, name: '박하은', availableParts: parkAvailability, phone: '010-9031-5527' }),
  person({ id: 6, name: '최도윤', phone: '010-4418-6603' }),
  person({ id: 7, name: '정유나', phone: '010-2205-7719' }),
  person({ id: 8, name: '강지호', phone: '010-6630-2284' }),
  person({ id: 9, name: '윤서연', phone: '010-8814-3356' }),
  person({ id: 10, name: '한태민', title: '연소자', isMinor: true, availableParts: everyDay(['OPEN', 'MIDDLE']), phone: '010-1197-4402' }),
];

let n = 0;
const date = (day: number) => addDays(SEED_WEEK, day);

/** 알바생 파트 근무 */
const p = (staffId: number, day: number, partId: string): Shift => {
  const part = parts.find((x) => x.id === partId)!;
  return placeBreak({
    id: `seed-${++n}`, staffId, date: date(day), start: part.start, end: part.end,
    partId, breakStart: null, breakMinutes: part.breakMinutes, tentative: false,
  });
};

/** 직원 유동 근무 */
const f = (staffId: number, day: number, start: string, end: string, breakStart: string, tentative = false): Shift => ({
  id: `seed-${++n}`, staffId, date: date(day), start, end, partId: null,
  breakStart, breakMinutes: minBreakFor(toMin(end) - toMin(start)), tentative,
});

const shifts: Shift[] = [
  // 오지훈 (매니저): 날마다 출근 시각이 다르다
  f(1, 0, '09:00', '18:00', '14:30'),
  f(1, 1, '12:30', '21:30', '15:00'),
  f(1, 3, '13:00', '22:00', '15:30'),
  f(1, 4, '11:00', '20:00', '14:00'),
  f(1, 5, '08:00', '17:00', '13:00'),
  // 서하린 (정직원): 목 연차, 토 시간 미정(AI 추천 12–21)
  f(2, 1, '07:30', '16:30', '14:00'),
  f(2, 2, '09:00', '18:00', '14:00'),
  f(2, 4, '13:00', '22:00', '16:00'),
  f(2, 5, '12:00', '21:00', '15:00', true),
  // 알바생
  p(3, 0, 'OPEN'), p(3, 2, 'OPEN'), p(3, 4, 'OPEN'), p(3, 6, 'CLOSE'),
  p(4, 0, 'MIDDLE'), p(4, 1, 'MIDDLE'), p(4, 3, 'MIDDLE'),
  p(5, 1, 'OPEN'), p(5, 3, 'OPEN'), p(5, 5, 'CLOSE'), p(5, 6, 'MIDDLE'),
  p(6, 0, 'CLOSE'), p(6, 1, 'CLOSE'), p(6, 3, 'CLOSE'), p(6, 5, 'CLOSE'),
  p(7, 2, 'MIDDLE'), p(7, 3, 'MIDDLE'), p(7, 4, 'MIDDLE'),
  p(8, 2, 'CLOSE'), p(8, 4, 'CLOSE'), p(8, 5, 'MIDDLE'), p(8, 6, 'CLOSE'),
  p(9, 0, 'CLOSE'), p(9, 2, 'CLOSE'), p(9, 6, 'MIDDLE'),
  p(10, 5, 'OPEN'), p(10, 6, 'OPEN'),
];

// 알바생 휴게는 피크를 피해 둔다
const brk = (staffId: number, day: number, at: string) => {
  shifts.find((s) => s.staffId === staffId && s.date === date(day))!.breakStart = at;
};
brk(4, 0, '14:00'); brk(4, 1, '16:00'); brk(4, 3, '14:00');
brk(7, 2, '15:00'); brk(7, 3, '15:00'); brk(7, 4, '15:00');
brk(5, 6, '14:30'); brk(9, 6, '15:30'); brk(8, 5, '14:30');
brk(6, 0, '20:00'); brk(9, 0, '20:30'); brk(6, 1, '20:00'); brk(6, 3, '20:00');
brk(8, 2, '20:00'); brk(9, 2, '20:30'); brk(6, 5, '20:00'); brk(5, 5, '20:30');
brk(3, 6, '20:00'); brk(8, 6, '20:30');
// 금 오픈 휴게는 오지훈 출근(11:00) 뒤로 (Figma 06과 같음)
brk(3, 4, '11:30');
// 금요일 강지호 휴게를 저녁 피크(19:30)에 둬서 커버리지 부족 예시를 만든다
brk(8, 4, '19:30');

const leaves: Leave[] = [{ id: 'seed-leave-1', staffId: 2, date: date(3), type: 'ANNUAL' }];

/** 한 주(11/2–11/8) 예시 */
export const seedWeek: Schedule = {
  id: 1,
  periodStart: SEED_WEEK,
  periodEnd: addDays(SEED_WEEK, 6),
  status: 'DRAFT',
  source: 'AI',
  policy: { weeklyHolidayPay: 'ALLOW', priority: 'FAIRNESS' },
  shifts,
  leaves,
  aiComment:
    '직원 2명은 계약 40시간에 맞춰 피크 시간대 중심으로 출근 시간을 배치했습니다. 서하린 님 토요일은 연차 사용 여부 확인이 필요해 시간을 미정으로 두었습니다. 금요일 저녁 19:30–20:00에 1명이 부족합니다. 박하은 님은 토요일 17시 이후 근무가 어려워 교체가 필요합니다.',
  updatedAt: '2026-10-29T09:00:00',
};
