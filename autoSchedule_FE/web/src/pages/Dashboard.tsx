import { Link } from 'react-router-dom';
import { useSchedules, useStaff, useStore } from '../api/queries';
import PageHeader from '../components/PageHeader';
import TodayStrip from '../components/TodayStrip';
import { Avatar, Badge, Button, Card, cx, Loading, StatStrip } from '../components/ui';
import { DAY_LABELS, datesBetween, dayLabel, hours, md, monthLabel, shortRange, toMin, weeksOf, won, ymd } from '../lib/time';
import { useValidation } from '../lib/useValidation';

export default function Dashboard() {
  const { data: store } = useStore();
  const { data: staff } = useStaff();
  const { data: schedules } = useSchedules();
  const current = schedules?.[0];
  const v = useValidation(current, staff, store);

  if (!schedules || !staff || !store) return <Loading />;

  const newButton = (
    <Link to="/schedules/new">
      <Button variant="primary">AI 근무표 생성</Button>
    </Link>
  );

  if (!current || !v)
    return (
      <div>
        <PageHeader title="대시보드" actions={newButton} />
        <Card className="p-10 text-center text-sm text-muted">아직 근무표가 없습니다. 조건만 고르면 AI가 한 달 근무표를 만들어 드려요.</Card>
      </div>
    );

  const fullTime = staff.filter((s) => s.employmentType === 'FULL_TIME');
  const now = new Date();
  const today = ymd(now);
  const dates = datesBetween(current.periodStart, current.periodEnd);
  // 오늘이 이 근무표 안이면 오늘, 아니면 근무표 첫날
  const day = dates.includes(today) ? today : dates[0];
  const dayShifts = current.shifts.filter((s) => s.date === day);
  const nowMin = day === today ? now.getHours() * 60 + now.getMinutes() : undefined;
  const working = nowMin === undefined ? [] : dayShifts.filter((s) => toMin(s.start) <= nowMin && nowMin < toMin(s.end)).map((s) => staff.find((p) => p.id === s.staffId)?.name);
  // 오늘이 든 주, 없으면 첫 온전한 주
  const weeks = weeksOf(dates);
  const week = weeks.find((w) => w.includes(today)) ?? weeks.find((w) => w.every(Boolean)) ?? weeks[0];
  const days = week.filter((d): d is string => d !== null);

  return (
    <div className="space-y-5">
      <PageHeader
        title="대시보드"
        sub={`${monthLabel(current.periodStart)} 근무표 · ${current.status === 'CONFIRMED' ? '확정됨' : '작성 중'}${v.tentativeCount ? ` · 미정 ${v.tentativeCount}건` : ''}`}
        actions={
          <>
            <Link to={`/schedules/${current.id}`}>
              <Button>근무표 열기</Button>
            </Link>
            {newButton}
          </>
        }
      />

      <Card className="px-6 pt-5 pb-6">
        <div className="mb-5 flex items-center justify-between gap-3">
          <div>
            <h2 className="font-bold">
              {day === today ? '오늘' : '첫날'} · {dayLabel(day)}
            </h2>
            <p className="text-xs text-muted">
              {new Set(dayShifts.map((s) => s.staffId)).size}명 근무
              {nowMin !== undefined && ` · 지금 ${working.length ? `${working.join('·')} 근무 중` : '근무자 없음'}`}
            </p>
          </div>
          <Link to={`/schedules/${current.id}?view=day&date=${day}`}>
            <Button variant="ghost" size="sm">
              일간 보기
            </Button>
          </Link>
        </div>
        <TodayStrip shifts={dayShifts} staff={staff} store={store} now={nowMin} />
      </Card>

      <StatStrip
        items={[
          { label: '이번 달 총 근무시간', value: hours(v.totals.workMin), sub: `직원 ${fullTime.length}명 · 알바생 ${staff.length - fullTime.length}명` },
          { label: '예상 인건비', value: won(v.totals.cost), sub: `주휴수당 ${won(v.totals.holidayPay)} 포함`, lead: true },
          { label: '주휴수당 대상', value: `${v.totals.weeklyHolidayCount}명`, sub: '알바생 주 15시간 이상' },
        ]}
      />

      {fullTime.length > 0 && (
        <Card>
          <div className="flex items-center justify-between border-b border-line px-6 py-4">
            <h2 className="font-bold">직원 근무 현황</h2>
            <span className="text-xs text-muted">
              {md(days[0])}–{md(days[days.length - 1])} · 출근 시간이 날마다 달라요
            </span>
          </div>
          <div className="divide-y divide-line">
            {fullTime.map((p) => {
              const w = v.weeks.get(days[0])!.get(p.id)!;
              // 게이지 기준: 법정 주 40시간
              const contract = 40 * 60;
              const seg = (m: number) => `${Math.min(100, (m / contract) * 100)}%`;
              return (
                <div key={p.id} className="grid items-center gap-4 px-6 py-4 lg:grid-cols-[240px_1fr_120px] lg:gap-6">
                  <div className="flex items-center gap-2.5">
                    <Avatar name={p.name} id={p.id} />
                    <div className="min-w-0">
                      <div className="flex items-center gap-1.5">
                        <span className="text-sm font-medium">{p.name}</span>
                        <Badge tone="staff">{p.title}</Badge>
                      </div>
                      <div className="text-xs text-muted">
                        {w.tentativeMin || w.leaveMin
                          ? [`확정 ${hours(w.workMin)}`, w.tentativeMin && `미정 ${hours(w.tentativeMin)}`, w.leaveMin && `연차 ${hours(w.leaveMin)}`].filter(Boolean).join(' · ')
                          : `확정 ${hours(w.workMin)}`}
                      </div>
                    </div>
                  </div>
                  <div className="grid grid-cols-7 gap-1.5 text-center text-xs">
                    {DAY_LABELS.map((d, i) => {
                      const s = current.shifts.find((x) => x.staffId === p.id && x.date === week[i]);
                      const leave = current.leaves.some((l) => l.staffId === p.id && l.date === week[i]);
                      return (
                        <div
                          key={d}
                          className={cx(
                            'rounded-md px-1 py-2',
                            !week[i] ? 'text-muted' : s?.tentative ? 'border-[1.5px] border-dashed border-staff text-staff' : s ? 'bg-staff-bg text-staff' : leave ? 'bg-brand-subtle text-muted' : 'border border-line text-muted',
                          )}
                        >
                          <div>{d}</div>
                          <div className="font-medium tabular-nums">{!week[i] ? '–' : s ? (s.tentative ? '미정' : shortRange(s.start, s.end)) : leave ? '연차' : '휴무'}</div>
                        </div>
                      );
                    })}
                  </div>
                  <div className="lg:text-right">
                    <div className="tabular-nums">
                      <span className="text-xl font-medium">{hours(w.workMin + w.tentativeMin + w.leaveMin)}</span>
                      <span className="text-xs text-muted"> / 40h</span>
                    </div>
                    <div className="mt-1.5 flex h-1.5 overflow-hidden rounded-full bg-brand-subtle">
                      <span className="bg-staff" style={{ width: seg(w.workMin) }} />
                      <span className="bg-staff-bg" style={{ width: seg(w.tentativeMin) }} />
                      <span className="bg-line-strong" style={{ width: seg(w.leaveMin) }} />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Card>
      )}
    </div>
  );
}
