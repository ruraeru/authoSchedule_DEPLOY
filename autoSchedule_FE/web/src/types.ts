// 백엔드 API(PRD §9, §10) 응답 형태를 따른다. 시각은 "HH:MM", 날짜는 "YYYY-MM-DD".

/** FULL_TIME = 직원(유동 근무, 계약 주 근로시간), PART_TIME = 알바생(파트 근무) */
export type EmploymentType = 'FULL_TIME' | 'PART_TIME';

export interface WorkTime {
  start: string;
  end: string;
}

export interface Staff {
  id: number;
  name: string;
  employmentType: EmploymentType;
  /** 화면 표시용 직함: 매니저, 정직원, 알바 등 */
  title: string;
  hourlyWage: number;
  isMinor: boolean;
  /** 알바생만 사용. 요일(0=월)별 고정 근무 파트 id. 고른 파트에는 반드시 배치한다 */
  availableParts: string[][];
  /** 직원만 사용. 고정 근무 시간(출근–퇴근). 없으면 날마다 자유롭게 정한다 */
  /** 직원 고정 근무. 요일별(0=월) 출퇴근 시각, null이면 쉬는 날. 전체가 null이면 시간 자유 */
  workHours?: (WorkTime | null)[] | null;
  phone: string;
}

export type PartTone = 'open' | 'middle' | 'close';

export interface Part {
  id: string;
  name: string;
  tone: PartTone;
  start: string;
  end: string;
  breakMinutes: number;
  /** 이 파트 시간대에 필요한 인원. 겹치는 파트는 더한다 */
  required: number;
}

export interface Store {
  id: number;
  name: string;
  branch: string;
  /** 사업장 규모 설정. AUTO면 구성원 수로 size를 정한다 */
  sizeMode: 'AUTO' | 'UNDER_5' | 'OVER_5';
  /** 실제 적용 규모. 저장할 때 sizeMode·구성원 수로 다시 정해진다 */
  size: 'UNDER_5' | 'OVER_5';
  /**
   * 평일 영업시간. 익일 마감은 close를 24시 넘겨 적는다(26:00 = 익일 02:00).
   * 백엔드도 같은 표기를 받는다(util/TimeCodec: DB에는 02:00으로 저장하고 시작보다 이르면 익일로 본다)
   */
  open: string;
  close: string;
  /** 주말·공휴일 영업시간. 익일 마감 표기는 평일과 같다 */
  weekendOpen: string;
  weekendClose: string;
  parts: Part[];
  minimumWage: number;
}

export interface Shift {
  id: string;
  staffId: number;
  date: string;
  start: string;
  end: string;
  /** 알바생 파트 근무면 파트 id, 직원 유동 근무면 null */
  partId: string | null;
  breakStart: string | null;
  breakMinutes: number;
  /** 직원 근무 시간 미정. start/end는 AI 추천값 */
  tentative: boolean;
}

export interface Leave {
  id: string;
  staffId: number;
  date: string;
  type: 'ANNUAL';
}

export type WeeklyHolidayPolicy = 'MINIMIZE' | 'ALLOW';
export type Priority = 'FAIRNESS' | 'COST';

export interface Schedule {
  id: number;
  periodStart: string;
  periodEnd: string;
  status: 'DRAFT' | 'CONFIRMED';
  source: 'AI' | 'TEMPLATE' | 'MANUAL';
  policy: { weeklyHolidayPay: WeeklyHolidayPolicy; priority: Priority };
  shifts: Shift[];
  leaves: Leave[];
  aiComment: string;
  updatedAt: string;
}

export interface GenerateRequest {
  periodStart: string;
  weeklyHolidayPay: WeeklyHolidayPolicy;
  priority: Priority;
}

export interface Template {
  id: number;
  name: string;
  createdAt: string;
  /** 날짜 대신 요일(0=월)로 저장해 다른 달의 같은 요일마다 적용한다 */
  shifts: (Omit<Shift, 'id' | 'date'> & { dayIndex: number })[];
  leaves: (Omit<Leave, 'id' | 'date'> & { dayIndex: number })[];
}

export type Severity = 'error' | 'warning' | 'info';

export interface Issue {
  ruleId: string;
  severity: Severity;
  message: string;
  staffId?: number;
  shiftId?: string;
  date?: string;
}

export interface User {
  name: string;
  email: string;
}
