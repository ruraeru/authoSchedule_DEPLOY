import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { keys, useSchedules } from '../api/queries';
import Icon from '../components/Icon';
import PageHeader from '../components/PageHeader';
import { Badge, Button, Card, Loading } from '../components/ui';
import { md, monthLabel } from '../lib/time';

const SOURCE = { AI: 'AI 생성', TEMPLATE: '템플릿', MANUAL: '직접 작성' };

export default function Schedules() {
  const { data } = useSchedules();
  const qc = useQueryClient();
  const remove = useMutation({
    mutationFn: api.deleteSchedule,
    onSuccess: (_, id) => {
      qc.removeQueries({ queryKey: keys.schedule(id) });
      qc.invalidateQueries({ queryKey: keys.schedules });
    },
  });
  if (!data) return <Loading />;
  return (
    <div>
      <PageHeader
        title="근무표"
        sub="월 단위 근무표 목록"
        actions={
          <>
            <Link to="/schedules/manual">
              <Button>직접 만들기</Button>
            </Link>
            <Link to="/schedules/new">
              <Button variant="primary">AI 근무표 생성</Button>
            </Link>
          </>
        }
      />
      {remove.error && <p className="mb-3 text-sm text-danger">{remove.error.message}</p>}
      <Card>
        {data.length === 0 && <p className="p-8 text-center text-sm text-muted">근무표가 없습니다.</p>}
        <ul className="divide-y divide-line">
          {data.map((s) => (
            <li key={s.id} className="flex items-center hover:bg-bg">
              <Link to={`/schedules/${s.id}`} className="flex flex-1 items-center gap-3 py-4 pl-6">
                <div className="flex-1">
                  <div className="text-sm font-medium">{monthLabel(s.periodStart)}</div>
                  <div className="text-xs text-muted">
                    {md(s.periodStart)} – {md(s.periodEnd)} · 근무 {s.shifts.length}건 · {SOURCE[s.source]}
                  </div>
                </div>
                {s.status === 'CONFIRMED' ? (
                  <span className="flex items-center gap-1 text-xs font-medium text-success">
                    <Icon name="check" size={14} />
                    확정
                  </span>
                ) : (
                  <Badge>작성 중</Badge>
                )}
                <Icon name="chevron-right" className="text-muted" />
              </Link>
              <div className="px-4">
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={remove.isPending}
                  aria-label={`${monthLabel(s.periodStart)} 근무표 삭제`}
                  onClick={() =>
                    window.confirm(`${monthLabel(s.periodStart)} 근무표를 삭제할까요?${s.status === 'CONFIRMED' ? ' 확정된 근무표입니다.' : ''} 되돌릴 수 없습니다.`) && remove.mutate(s.id)
                  }
                >
                  삭제
                </Button>
              </div>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
