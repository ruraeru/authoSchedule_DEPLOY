import { useState } from 'react';
import { minBreakFor, placeBreak } from '../../lib/rules';
import { dayLabel, fromMin, toMin } from '../../lib/time';
import { useEditor } from '../../store/editor';
import type { Shift, Staff, Store } from '../../types';
import { Button, Field, inputClass, Modal, Segmented } from '../../components/ui';

export interface DialogTarget {
  date: string;
  staffId?: number;
  shift?: Shift;
  partId?: string | null;
}

const TIMES = Array.from({ length: (24 - 6) * 4 + 1 }, (_, i) => fromMin(6 * 60 + i * 15));

type Mode = 'work' | 'leave' | 'off';

export default function ShiftDialog({ target, staff, store, onClose }: { target: DialogTarget; staff: Staff[]; store: Store; onClose: () => void }) {
  const edit = useEditor((s) => s.edit);
  const draft = useEditor((s) => s.draft)!;
  const { shift, date } = target;
  const firstPart = store.parts.find((p) => p.id === target.partId);

  const [staffId, setStaffId] = useState(shift?.staffId ?? target.staffId ?? staff.find((s) => s.employmentType === (firstPart ? 'PART_TIME' : 'FULL_TIME'))?.id ?? staff[0].id);
  const person = staff.find((s) => s.id === staffId)!;
  const fullTime = person.employmentType === 'FULL_TIME';
  const onLeave = draft.leaves.some((l) => l.staffId === staffId && l.date === date);

  const [mode, setMode] = useState<Mode>(onLeave && !shift ? 'leave' : 'work');
  const [partId, setPartId] = useState<string | null>(shift ? shift.partId : (firstPart?.id ?? null));
  const [start, setStart] = useState(shift?.start ?? firstPart?.start ?? '09:00');
  const [end, setEnd] = useState(shift?.end ?? firstPart?.end ?? '18:00');
  const [breakMinutes, setBreakMinutes] = useState(shift?.breakMinutes ?? minBreakFor(toMin(end) - toMin(start)));
  const [breakStart, setBreakStart] = useState(shift?.breakStart ?? '');
  const [tentative, setTentative] = useState(shift?.tentative ?? false);

  const setTimes = (s: string, e: string) => {
    setStart(s);
    setEnd(e);
    setBreakMinutes(minBreakFor(toMin(e) - toMin(s)));
    setBreakStart('');
  };
  const choosePart = (id: string) => {
    if (id === '') return setPartId(null);
    const p = store.parts.find((x) => x.id === id)!;
    setPartId(p.id);
    setTimes(p.start, p.end);
    setBreakMinutes(p.breakMinutes);
  };
  const valid = mode !== 'work' || toMin(end) > toMin(start);

  const save = () => {
    edit((s) => {
      s.shifts = s.shifts.filter((x) => x.id !== shift?.id && !(mode !== 'work' && x.staffId === staffId && x.date === date));
      s.leaves = s.leaves.filter((l) => !(l.staffId === staffId && l.date === date));
      if (mode === 'leave') s.leaves.push({ id: crypto.randomUUID(), staffId, date, type: 'ANNUAL' });
      if (mode === 'work')
        s.shifts.push(
          placeBreak({
            id: shift?.id ?? crypto.randomUUID(),
            staffId,
            date,
            start,
            end,
            partId: fullTime ? null : partId,
            breakMinutes,
            breakStart: breakStart || null,
            tentative: fullTime && tentative,
          }),
        );
    });
    onClose();
  };

  const remove = () => {
    edit((s) => {
      s.shifts = s.shifts.filter((x) => x.id !== shift!.id);
    });
    onClose();
  };

  return (
    <Modal
      title={`${dayLabel(date)} ${shift ? '근무 수정' : '근무 추가'}`}
      onClose={onClose}
      footer={
        <>
          {shift && (
            <Button variant="danger" className="mr-auto" onClick={remove}>
              삭제
            </Button>
          )}
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" onClick={save} disabled={!valid}>
            저장
          </Button>
        </>
      }
    >
      <Field label="구성원">
        <select className={inputClass} value={staffId} onChange={(e) => setStaffId(Number(e.target.value))} disabled={!!shift}>
          {staff.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} ({s.employmentType === 'FULL_TIME' ? '직원' : '알바'}
              {s.isMinor ? ', 연소자' : ''})
            </option>
          ))}
        </select>
      </Field>

      {fullTime && (
        <Segmented<Mode>
          value={mode}
          onChange={setMode}
          options={[
            { value: 'work', label: '근무' },
            { value: 'leave', label: '연차' },
            { value: 'off', label: '휴무' },
          ]}
        />
      )}

      {mode === 'work' && (
        <>
          {!fullTime && (
            <Field label="파트">
              <select className={inputClass} value={partId ?? ''} onChange={(e) => choosePart(e.target.value)}>
                {store.parts.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} {p.start}–{p.end}
                  </option>
                ))}
                <option value="">직접 입력</option>
              </select>
            </Field>
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="출근">
              <select className={inputClass} value={start} onChange={(e) => setTimes(e.target.value, end)}>
                {TIMES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </Field>
            <Field label="퇴근">
              <select className={inputClass} value={end} onChange={(e) => setTimes(start, e.target.value)}>
                {TIMES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="휴게" hint={`법정 최소 ${minBreakFor(toMin(end) - toMin(start))}분`}>
              <select className={inputClass} value={breakMinutes} onChange={(e) => setBreakMinutes(Number(e.target.value))}>
                {[0, 30, 60, 90, 120].map((m) => (
                  <option key={m} value={m}>
                    {m}분
                  </option>
                ))}
              </select>
            </Field>
            <Field label="휴게 시작" hint="비우면 근무 중간">
              <select className={inputClass} value={breakStart} onChange={(e) => setBreakStart(e.target.value)} disabled={!breakMinutes}>
                <option value="">자동</option>
                {TIMES.filter((t) => toMin(t) > toMin(start) && toMin(t) + breakMinutes < toMin(end)).map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </Field>
          </div>
          {fullTime && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={tentative} onChange={(e) => setTentative(e.target.checked)} className="size-4 accent-brand" />
              시간 미정 (확정 전 본인 확인 필요)
            </label>
          )}
        </>
      )}
      {mode === 'leave' && <p className="text-sm text-muted">연차는 유급 8시간으로 계산합니다. 이날 근무는 삭제됩니다.</p>}
      {mode === 'off' && <p className="text-sm text-muted">이날 근무와 연차를 모두 비웁니다.</p>}
    </Modal>
  );
}
