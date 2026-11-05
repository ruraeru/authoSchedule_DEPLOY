// AI 근무표 생성: 프롬프트 만들기와 응답 검증(둘 다 브라우저). 호출은 서버(api/generate.ts)가 한다
import type { GenerateRequest, Shift, Staff, Store } from '../types';
import { canWork, hoursOn, sortParts, withTimes } from './rules';
import { datesBetween, dayIndex, DAY_LABELS, fromMin, isWeekendOrHoliday, monthEnd, snap, toMin } from './time';

export interface AiInput {
  store: Store;
  staff: Staff[];
  req: GenerateRequest;
}

export interface AiShift {
  staffId: number;
  date: string;
  start: string;
  end: string;
  /** 직원만 받으니 쓰지 않는다. 예전 응답 호환용 */
  partId?: string | null;
}

export interface AiOutput {
  shifts: AiShift[];
  comment: string;
}

/** 이름·연락처는 빼고 번호로만 보낸다 */
export function buildPrompt({ store, staff, req }: AiInput): string {
  const dates = datesBetween(req.periodStart, monthEnd(req.periodStart));
  const people = staff.map((s) => {
    const base = `- #${s.id} ${s.employmentType === 'FULL_TIME' ? '직원' : '알바생'}${s.isMinor ? '(연소자)' : ''}`;
    if (s.employmentType === 'FULL_TIME')
      return `${base}, ${s.workHours ? `고정 근무(요일별, 쉼인 요일은 근무 없음): ${DAY_LABELS.map((d, i) => `${d} ${s.workHours![i] ? `${s.workHours![i]!.start}–${s.workHours![i]!.end}` : '쉼'}`).join(' / ')}` : '영업시간 안이면 언제나 근무 가능'}`;
    const fixed = DAY_LABELS.map((d, i) => `${d} ${(s.availableParts[i] ?? []).map((id) => `"${id}"`).join('·') || '쉼'}`).join(' / ');
    return `${base}, 고정 근무 파트(partId): ${fixed}`;
  });
  const days = dates.map((d) => {
    const h = hoursOn(store, d);
    const holiday = isWeekendOrHoliday(d) && dayIndex(d) < 5;
    return `${d} (${DAY_LABELS[dayIndex(d)]}${holiday ? ', 공휴일' : ''}) ${h.open}–${h.close}${toMin(h.close) > 24 * 60 ? ` (24시 이후는 익일, ${h.close} = 익일 ${fromMin(toMin(h.close) - 24 * 60)})` : ''}`;
  });

  return `당신은 한국 매장의 한 달 근무표를 짜는 도우미입니다. 아래 조건으로 ${req.periodStart.slice(0, 7)} 한 달 근무표를 JSON으로 만드세요.

## 영업일과 영업시간
${days.join('\n')}

## 근무 파트와 필요 인원 (휴게 중인 사람은 빼고 셈, 겹치는 파트는 더함)
${sortParts(store.parts).map((p) => `- partId "${p.id}" ${p.name} ${p.start}–${p.end} 필요 ${p.required}명`).join('\n') || '- 없음'}

## 구성원 (#번호 = staffId)
${people.join('\n')}

## 규칙
1. 알바생은 고정 근무 파트대로 시스템이 넣는다. shifts에는 직원만 넣는다.
2. 고정 근무가 있으면 근무 요일마다 start/end를 그 요일 시각과 똑같이 넣는다. 없으면 출퇴근 시각을 15분 단위로 정해 알바생 고정 근무로 모자란 인원을 메운다.
3. 직원 근무는 그날 영업시간 안에서만 한다. 한 사람은 하루에 한 번만 근무한다.
4. 주(월~일) 근로시간은 40시간 이하, 연장 포함 52시간 이하.
5. ${req.priority === 'FAIRNESS' ? '직원끼리 근무시간 차이를 줄인다.' : '필요 인원만큼만 최소로 배치해 인건비를 줄인다.'}
6. 휴게시간은 시스템이 자동으로 넣으니 출근~퇴근 시각만 주면 된다.

comment에는 배치 방식을 사장님께 2~3문장으로 요약한다. 구성원은 #번호로만 부른다.`;
}

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$|^24:00$/;

/** AI 응답을 믿지 않고 걸러 근무로 만든다. 규칙에 안 맞는 줄은 버린다 */
export function toShifts(out: AiOutput, { store, staff, req }: AiInput, uid: () => string): Shift[] {
  const dates = new Set(datesBetween(req.periodStart, monthEnd(req.periodStart)));
  const seen = new Set<string>();
  const shifts: Shift[] = [];
  for (const a of Array.isArray(out?.shifts) ? out.shifts : []) {
    const person = staff.find((s) => s.id === a.staffId);
    if (!person || !dates.has(a.date) || seen.has(`${a.staffId}|${a.date}`)) continue;
    const base: Shift = { id: uid(), staffId: person.id, date: a.date, start: '', end: '', partId: null, breakStart: null, breakMinutes: 0, tentative: false };
    let start: string;
    let end: string;
    // 알바생은 고정 근무 파트로 따로 넣는다
    if (person.employmentType === 'PART_TIME') continue;
    if (person.workHours) {
      const w = person.workHours[dayIndex(a.date)];
      if (!w) continue;
      [start, end] = [w.start, w.end];
    } else {
      if (!HHMM.test(a.start) || !HHMM.test(a.end)) continue;
      [start, end] = [fromMin(snap(toMin(a.start))), fromMin(snap(toMin(a.end)))];
    }
    const h = hoursOn(store, a.date);
    if (toMin(start) >= toMin(end) || toMin(start) < toMin(h.open) || toMin(end) > toMin(h.close)) continue;
    if (!canWork(person, store, { date: a.date, start, end, partId: base.partId })) continue;
    seen.add(`${a.staffId}|${a.date}`);
    shifts.push(withTimes(base, start, end));
  }
  return shifts;
}
