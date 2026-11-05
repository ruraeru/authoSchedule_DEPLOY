export const DAY_LABELS = ['월', '화', '수', '목', '금', '토', '일'];

export const toMin = (t: string) => {
  const [h, m] = t.split(':').map(Number);
  return h * 60 + m;
};

const pad = (n: number) => String(n).padStart(2, '0');

export const fromMin = (m: number) => `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;

export const snap = (m: number, step = 15) => Math.round(m / step) * step;

export const ymd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

const parse = (date: string) => new Date(`${date}T00:00:00`);

export const addDays = (date: string, n: number) => {
  const d = parse(date);
  d.setDate(d.getDate() + n);
  return ymd(d);
};

/** 0=월 ... 6=일 */
export const dayIndex = (date: string) => (parse(date).getDay() + 6) % 7;

export const mondayOf = (date: string) => addDays(date, -dayIndex(date));

export const weekDates = (start: string) => Array.from({ length: 7 }, (_, i) => addDays(start, i));

export const diffDays = (a: string, b: string) => Math.round((parse(b).getTime() - parse(a).getTime()) / 86400000);

/** "11/6" */
export const md = (date: string) => `${Number(date.slice(5, 7))}/${Number(date.slice(8))}`;

/** "금 11/6" */
export const dayLabel = (date: string) => `${DAY_LABELS[dayIndex(date)]} ${md(date)}`;

// shortcut: 2026년 공휴일·대체공휴일만 하드코딩. 다른 해도 쓰려면 공공데이터 특일정보 API로 바꾼다.
const HOLIDAYS = new Set([
  '2026-01-01', '2026-02-16', '2026-02-17', '2026-02-18', '2026-03-01', '2026-03-02', '2026-05-01', '2026-05-05',
  '2026-05-24', '2026-05-25', '2026-06-03', '2026-06-06', '2026-07-17', '2026-08-15', '2026-08-17', '2026-09-24',
  '2026-09-25', '2026-09-26', '2026-10-03', '2026-10-05', '2026-10-09', '2026-12-25',
]);

/** 토·일 또는 공휴일 */
export const isWeekendOrHoliday = (date: string) => dayIndex(date) >= 5 || HOLIDAYS.has(date);

/** 그 달 1일 */
export const monthOf = (date: string) => `${date.slice(0, 7)}-01`;

/** 1일 기준 n달 뒤 1일 */
export const addMonths = (start: string, n: number) => {
  const d = parse(start);
  return ymd(new Date(d.getFullYear(), d.getMonth() + n, 1));
};

/** 그 달 말일 */
export const monthEnd = (start: string) => {
  const d = parse(start);
  return ymd(new Date(d.getFullYear(), d.getMonth() + 1, 0));
};

/** "2026년 11월" */
export const monthLabel = (start: string) => `${start.slice(0, 4)}년 ${Number(start.slice(5, 7))}월`;

export const datesBetween = (a: string, b: string) => Array.from({ length: diffDays(a, b) + 1 }, (_, i) => addDays(a, i));

/** 기간을 월~일 주로 나눈다. 기간 밖 요일 칸은 null */
export const weeksOf = (dates: string[]) => {
  const weeks: (string | null)[][] = [];
  for (const d of dates) {
    if (!weeks.length || dayIndex(d) === 0) weeks.push(Array(7).fill(null));
    weeks[weeks.length - 1][dayIndex(d)] = d;
  }
  return weeks;
};

/** 분 → "13.5h" */
export const hours = (min: number) => `${Math.round((min / 60) * 10) / 10}h`;

export const won = (n: number) => `${Math.round(n).toLocaleString('ko-KR')}원`;

/** "12–21시" 또는 "10:30–19:30" */
export const shortRange = (start: string, end: string) =>
  start.endsWith(':00') && end.endsWith(':00') ? `${Number(start.slice(0, 2))}–${Number(end.slice(0, 2))}시` : `${start}–${end}`;
