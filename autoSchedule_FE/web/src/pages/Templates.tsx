import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { keys, useTemplates } from '../api/queries';
import PageHeader from '../components/PageHeader';
import { Button, Card, inputClass, Loading } from '../components/ui';
import { addMonths, monthLabel, monthOf, ymd } from '../lib/time';

export default function Templates() {
  const { data } = useTemplates();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const thisMonth = monthOf(ymd(new Date()));
  const months = [0, 1, 2, 3].map((i) => addMonths(thisMonth, i));
  const [month, setMonth] = useState(months[1]);

  const apply = useMutation({
    mutationFn: (id: number) => api.applyTemplate(id, month),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: keys.schedules });
      navigate(`/schedules/${s.id}`);
    },
  });
  const remove = useMutation({
    mutationFn: api.deleteTemplate,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.templates }),
  });

  if (!data) return <Loading />;
  return (
    <div className="max-w-3xl">
      <PageHeader
        title="템플릿"
        sub="근무표의 한 주 요일 패턴을 저장해 두고 다른 달의 같은 요일마다 적용합니다. 근무표 화면의 '템플릿으로 저장'으로 만듭니다."
        actions={
          <select className={inputClass} value={month} onChange={(e) => setMonth(e.target.value)} aria-label="적용할 달">
            {months.map((m) => (
              <option key={m} value={m}>
                {monthLabel(m)}에 적용
              </option>
            ))}
          </select>
        }
      />
      <Card>
        {data.length === 0 && <p className="p-8 text-center text-sm text-muted">저장된 템플릿이 없습니다.</p>}
        <ul className="divide-y divide-line">
          {data.map((t) => (
            <li key={t.id} className="flex items-center gap-2 px-6 py-4">
              <div className="flex-1">
                <div className="text-sm font-medium">{t.name}</div>
                <div className="text-xs text-muted">
                  근무 {t.shifts.length}건 · 직원 유동 근무 {t.shifts.filter((s) => !s.partId).length}건 · 연차 {t.leaves.length}건
                </div>
              </div>
              <Button size="sm" variant="ghost" onClick={() => remove.mutate(t.id)}>
                삭제
              </Button>
              <Button size="sm" onClick={() => apply.mutate(t.id)} disabled={apply.isPending}>
                적용
              </Button>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
