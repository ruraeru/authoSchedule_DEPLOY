// 백엔드 없이 동작하는 브라우저 DB. localStorage에 보관해 새로고침해도 유지한다.
import { fixedShifts, sizeOf, sortParts, sortShifts } from '../lib/rules';
import { buildPrompt, toShifts, type AiInput, type AiOutput } from '../lib/ai';
import { datesBetween, dayIndex, monthEnd, weeksOf } from '../lib/time';
import type { GenerateRequest, Schedule, Shift, Staff, Store, Template, Part } from '../types';
import { defaultStore } from './defaults';

// v6: 근무 가능 시간 → 근무 가능 파트
const KEY = 'autoschedule-mock-v6';

interface Db {
  store: Store;
  staff: Staff[];
  schedules: Schedule[];
  templates: Template[];
}

const uid = () => crypto.randomUUID();

type Pattern = Pick<Template, 'shifts' | 'leaves'>;

/** 근무표의 첫 번째 온전한 주(월~일)를 요일 패턴으로 */
function toPattern(s: Schedule): Pattern {
  const week = weeksOf(datesBetween(s.periodStart, s.periodEnd)).find((w) => w.every(Boolean)) ?? [];
  const strip = <T extends { id: string; date: string }>({ id: _id, date, ...rest }: T) => ({ ...rest, dayIndex: dayIndex(date) });
  return {
    shifts: s.shifts.filter((x) => week.includes(x.date)).map(strip) as Pattern['shifts'],
    leaves: s.leaves.filter((x) => week.includes(x.date)).map(strip) as Pattern['leaves'],
  };
}

/** 요일 패턴을 그 달 같은 요일마다 반복. 연차는 그 요일 첫 날에만 둔다 */
function fillMonth(start: string, p: Pattern): Pick<Schedule, 'periodStart' | 'periodEnd' | 'shifts' | 'leaves'> {
  const dates = datesBetween(start, monthEnd(start));
  return {
    periodStart: start,
    periodEnd: monthEnd(start),
    shifts: dates.flatMap((date) => p.shifts.filter((s) => s.dayIndex === dayIndex(date)).map(({ dayIndex: _d, ...rest }): Shift => ({ ...rest, id: uid(), date }))),
    leaves: p.leaves.flatMap(({ dayIndex: d, ...rest }) => {
      const date = dates.find((x) => dayIndex(x) === d);
      return date ? [{ ...rest, id: uid(), date }] : [];
    }),
  };
}

const fresh = (): Db => structuredClone({ store: defaultStore, staff: [], schedules: [], templates: [] });

/** 예전 형식으로 저장된 구성원(근무 가능 파트 없음)은 알바생이면 전체 파트 가능으로 채운다 */
export function upgrade(d: Db): Db {
  // 예전 시간대별 필요 인원(demand) → 파트별 필요 인원
  d.store.parts = d.store.parts.map((p) => ({ ...p, required: p.required ?? 1 }));
  delete (d.store as Partial<{ demand: unknown }>).demand;
  // 평일·주말 분리를 잠깐 썼던 데이터: 같은 시각의 주말 복사본(id_WE)을 원래 파트로 합친다
  const twin = (id: string | null) => (id?.endsWith('_WE') && d.store.parts.some((p) => p.id === id.slice(0, -3)) ? id.slice(0, -3) : id);
  d.store.parts = d.store.parts.filter((p) => twin(p.id) === p.id).map(({ days: _d, ...p }: Part & { days?: unknown }) => p);
  d.staff = d.staff.map((s) => (Array.isArray(s.availableParts) ? { ...s, availableParts: s.availableParts.map((row) => [...new Set(row.map((id) => twin(id)!))]) } : s));
  d.schedules.forEach((sc) => (sc.shifts = sc.shifts.map((s) => ({ ...s, partId: twin(s.partId) }))));
  d.templates.forEach((t) => (t.shifts = t.shifts.map((s) => ({ ...s, partId: twin(s.partId) }))));
  d.store.parts = sortParts(d.store.parts);
  const all = d.store.parts.map((p) => p.id);
  d.staff = d.staff.map((s) => (Array.isArray(s.availableParts) ? s : { ...s, availableParts: Array.from({ length: 7 }, () => (s.employmentType === 'PART_TIME' ? [...all] : [])) }));
  // 예전 고정 근무 {start, end, days?} → 요일별 배열
  d.staff = d.staff.map((s) => {
    const w = s.workHours as unknown as { start: string; end: string; days?: number[] } | null | undefined;
    if (!w || Array.isArray(w)) return s;
    return { ...s, workHours: Array.from({ length: 7 }, (_, i) => (!w.days || w.days.includes(i) ? { start: w.start, end: w.end } : null)) };
  });
  // 예전 데이터는 규모를 고를 수 없었으므로 자동 판정으로 시작한다
  d.store.sizeMode ??= 'AUTO';
  d.store.size = sizeOf(d.store.sizeMode, d.staff.length);
  return d;
}

function load(): Db {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return upgrade(JSON.parse(raw) as Db);
  } catch {
    // 저장소 접근 불가(사생활 보호 모드 등) → 시드 사용
  }
  return fresh();
}

let db = load();

const persist = () => {
  try {
    localStorage.setItem(KEY, JSON.stringify(db));
  } catch {
    // 저장 실패는 무시: 메모리 상태로 계속 동작
  }
};

const delay = <T>(value: T, ms = 250) => new Promise<T>((r) => setTimeout(() => r(structuredClone(value)), ms));
const notFound = () => Promise.reject(new Error('찾을 수 없습니다'));
export const mock = {
  getStore: () => delay(db.store),
  updateStore(store: Store) {
    // 지운 파트를 가리키던 데이터 정리: 근무는 파트 없는 근무로 남기고, 구성원 가능 파트에서는 뺀다
    const ids = new Set(store.parts.map((p) => p.id));
    const clean = <T extends { partId: string | null }>(s: T) => (s.partId && !ids.has(s.partId) ? { ...s, partId: null } : s);
    db.schedules.forEach((sc) => (sc.shifts = sc.shifts.map(clean)));
    db.templates.forEach((t) => (t.shifts = t.shifts.map(clean)));
    db.staff = db.staff.map((s) => ({ ...s, availableParts: s.availableParts.map((day) => day.filter((id) => ids.has(id))) }));
    db.store = { ...store, parts: sortParts(store.parts), size: sizeOf(store.sizeMode, db.staff.length) };
    persist();
    return delay(db.store);
  },

  listStaff: () => delay(db.staff),
  saveStaff(staff: Staff) {
    if (staff.id) db.staff = db.staff.map((s) => (s.id === staff.id ? staff : s));
    else db.staff.push({ ...staff, id: Math.max(0, ...db.staff.map((s) => s.id)) + 1 });
    db.store.size = sizeOf(db.store.sizeMode, db.staff.length);
    persist();
    return delay(staff);
  },
  deleteStaff(id: number) {
    db.staff = db.staff.filter((s) => s.id !== id);
    db.store.size = sizeOf(db.store.sizeMode, db.staff.length);
    db.schedules.forEach((sc) => {
      sc.shifts = sc.shifts.filter((s) => s.staffId !== id);
      sc.leaves = sc.leaves.filter((l) => l.staffId !== id);
    });
    persist();
    return delay(null);
  },

  listSchedules: () => delay([...db.schedules].sort((a, b) => b.periodStart.localeCompare(a.periodStart))),
  getSchedule(id: number) {
    const s = db.schedules.find((x) => x.id === id);
    return s ? delay(s) : notFound();
  },
  saveSchedule(id: number, body: Pick<Schedule, 'shifts' | 'leaves'>) {
    const s = db.schedules.find((x) => x.id === id);
    if (!s) return notFound();
    Object.assign(s, body, { updatedAt: new Date().toISOString() });
    persist();
    return delay(s);
  },
  confirmSchedule(id: number) {
    const s = db.schedules.find((x) => x.id === id);
    if (!s) return notFound();
    s.status = 'CONFIRMED';
    persist();
    return delay(s);
  },

  /** 직접 작성할 빈 근무표. prefillFixed면 직원 고정 근무를 미리 넣는다 */
  createSchedule(periodStart: string, prefillFixed: boolean) {
    const dates = datesBetween(periodStart, monthEnd(periodStart));
    const schedule: Schedule = {
      id: Math.max(0, ...db.schedules.map((s) => s.id)) + 1,
      periodStart,
      periodEnd: monthEnd(periodStart),
      status: 'DRAFT',
      source: 'MANUAL',
      policy: { weeklyHolidayPay: 'ALLOW', priority: 'FAIRNESS' },
      shifts: prefillFixed ? fixedShifts(db.store, db.staff, dates, uid) : [],
      leaves: [],
      aiComment: '',
      updatedAt: new Date().toISOString(),
    };
    db.schedules = db.schedules.filter((s) => s.periodStart !== periodStart || s.status === 'CONFIRMED');
    db.schedules.push(schedule);
    persist();
    return delay(schedule);
  },

  /** AI 서버(/ai/generate → api/generate.ts)로 만들고, 응답은 규칙에 맞는 근무만 남긴다 */
  async generateSchedule(req: GenerateRequest) {
    if (!db.staff.length) throw new Error('직원·알바생을 먼저 등록하세요');
    const input: AiInput = { store: db.store, staff: db.staff, req };
    const res = await fetch('/ai/generate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt: buildPrompt(input) }) });
    const out = (await res.json().catch(() => ({}))) as AiOutput & { error?: string };
    if (!res.ok) throw new Error(out.error ?? `${res.status} ${res.statusText}`);
    const partTimers = db.staff.filter((s) => s.employmentType === 'PART_TIME');
    const dates = datesBetween(req.periodStart, monthEnd(req.periodStart));
    // 알바생은 고정 근무 파트대로, 직원은 AI 응답으로
    const shifts = sortShifts([...fixedShifts(db.store, partTimers, dates, uid), ...toShifts(out, input, uid)]);
    if (!shifts.length) throw new Error('AI가 조건에 맞는 근무를 만들지 못했습니다');
    const schedule: Schedule = {
      id: Math.max(0, ...db.schedules.map((s) => s.id)) + 1,
      periodStart: req.periodStart,
      periodEnd: monthEnd(req.periodStart),
      status: 'DRAFT',
      source: 'AI',
      policy: { weeklyHolidayPay: req.weeklyHolidayPay, priority: req.priority },
      shifts,
      leaves: [],
      // AI에는 번호만 보냈으니 이름으로 되돌린다
      aiComment: typeof out.comment === 'string' ? out.comment.replace(/#(\d+)/g, (m, id) => db.staff.find((s) => s.id === Number(id))?.name ?? m) : '',
      updatedAt: new Date().toISOString(),
    };
    db.schedules = db.schedules.filter((s) => s.periodStart !== req.periodStart || s.status === 'CONFIRMED');
    db.schedules.push(schedule);
    persist();
    return delay(schedule);
  },

  listTemplates: () => delay(db.templates),
  saveTemplate(name: string, scheduleId: number) {
    const s = db.schedules.find((x) => x.id === scheduleId);
    if (!s) return notFound();
    const t: Template = {
      id: Math.max(0, ...db.templates.map((x) => x.id)) + 1,
      name,
      createdAt: new Date().toISOString(),
      ...toPattern(s),
    };
    db.templates.push(t);
    persist();
    return delay(t);
  },
  deleteSchedule(id: number) {
    db.schedules = db.schedules.filter((s) => s.id !== id);
    persist();
    return delay(null);
  },
  deleteTemplate(id: number) {
    db.templates = db.templates.filter((t) => t.id !== id);
    persist();
    return delay(null);
  },
  applyTemplate(id: number, periodStart: string) {
    const t = db.templates.find((x) => x.id === id);
    if (!t) return notFound();
    const schedule: Schedule = {
      id: Math.max(0, ...db.schedules.map((s) => s.id)) + 1,
      ...fillMonth(periodStart, t),
      status: 'DRAFT',
      source: 'TEMPLATE',
      policy: { weeklyHolidayPay: 'ALLOW', priority: 'FAIRNESS' },
      aiComment: `템플릿 "${t.name}"을 적용했습니다.`,
      updatedAt: new Date().toISOString(),
    };
    db.schedules = db.schedules.filter((s) => s.periodStart !== periodStart || s.status === 'CONFIRMED');
    db.schedules.push(schedule);
    persist();
    return delay(schedule);
  },
};
