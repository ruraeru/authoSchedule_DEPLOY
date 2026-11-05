import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { keys, useSchedules, useStaff } from '../api/queries';
import PageHeader from '../components/PageHeader';
import { Button, Card, Loading } from '../components/ui';
import { addMonths, md, monthEnd, monthLabel, monthOf, ymd } from '../lib/time';
import { Choice, Section } from './Generate';

/** AI 없이 빈 근무표를 만들고 에디터에서 직접 채운다 */
export default function CreateSchedule() {
  const { data: staff } = useStaff();
  const { data: schedules } = useSchedules();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const thisMonth = monthOf(ymd(new Date()));
  const months = [0, 1, 2, 3].map((i) => addMonths(thisMonth, i));
  const [month, setMonth] = useState(months[1]);
  const [prefill, setPrefill] = useState(true);

  const create = useMutation({
    mutationFn: () => api.createSchedule(month, prefill),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: keys.schedules });
      navigate(`/schedules/${s.id}`);
    },
  });

  if (!staff || !schedules) return <Loading />;
  const fixed = staff.filter((s) => s.employmentType === 'FULL_TIME' && s.workHours?.some(Boolean));
  // 같은 달의 작성 중 근무표는 새 근무표로 바뀐다 (확정된 건 남는다)
  const draft = schedules.find((s) => s.periodStart === month && s.status !== 'CONFIRMED');
  const submit = () => (!draft || window.confirm(`${monthLabel(month)} 작성 중 근무표(근무 ${draft.shifts.length}건)를 지우고 새로 만들까요?`)) && create.mutate();

  return (
    <div className="max-w-3xl">
      <PageHeader title="근무표 직접 만들기" sub="빈 근무표를 만든 뒤 에디터에서 칸을 눌러 근무를 넣거나, 자동 채우기로 빈 곳을 채웁니다." />
      <Card className="space-y-7 p-6">
        <Section title="기간">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {months.map((m) => (
              <Choice key={m} active={month === m} onClick={() => setMonth(m)} title={monthLabel(m)} desc={`${md(m)} – ${md(monthEnd(m))}`} />
            ))}
          </div>
        </Section>
        <Section title="시작 내용">
          <label className="flex items-start gap-2.5 rounded-[10px] border border-line-strong p-4">
            <input type="checkbox" className="mt-0.5 size-4 accent-brand" checked={prefill} onChange={(e) => setPrefill(e.target.checked)} />
            <span>
              <span className="block text-sm font-bold">직원 고정 근무 미리 넣기</span>
              <span className="mt-0.5 block text-xs text-muted">
                {fixed.length ? `${fixed.map((s) => s.name).join(', ')}님의 요일별 고정 근무를 한 달 내내 채워 둡니다.` : '고정 근무가 등록된 직원이 없습니다.'} 끄면 완전히 빈 근무표로 시작합니다.
              </span>
            </span>
          </label>
        </Section>
        {draft && <p className="rounded-[10px] bg-danger-subtle px-4 py-3 text-sm text-danger">{monthLabel(month)}에 작성 중인 근무표가 있어요. 만들면 새 근무표로 바뀝니다.</p>}
        {create.error && <p className="text-sm text-danger">만들기 실패: {create.error.message}</p>}
        <div className="flex justify-end">
          <Button variant="primary" onClick={submit} disabled={create.isPending}>
            {create.isPending ? '만드는 중…' : '근무표 만들기'}
          </Button>
        </div>
      </Card>
    </div>
  );
}
