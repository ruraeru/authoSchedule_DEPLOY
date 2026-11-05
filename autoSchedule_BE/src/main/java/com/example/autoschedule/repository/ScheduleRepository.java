package com.example.autoschedule.repository;

import com.example.autoschedule.entity.Schedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /** 근무표 목록: 최신 달이 먼저 */
    @EntityGraph(attributePaths = {"shifts", "shifts.worker", "shifts.part"})
    List<Schedule> findByStoreIdOrderByTargetYearMonthDesc(Long storeId);

    @EntityGraph(attributePaths = {"shifts", "shifts.worker", "shifts.part"})
    Optional<Schedule> findByIdAndStoreId(Long id, Long storeId);

    List<Schedule> findByStoreIdAndTargetYearMonth(Long storeId, String targetYearMonth);

    /** 월간 조회: 확정본이 있으면 그것, 없으면 작성 중 */
    Optional<Schedule> findFirstByStoreIdAndTargetYearMonthOrderByStatusDescIdDesc(Long storeId, String targetYearMonth);

    /** 같은 달 정리 규칙: 새로 만들 때 기존 DRAFT 삭제 (CONFIRMED는 유지) */
    List<Schedule> findByStoreIdAndTargetYearMonthAndStatus(Long storeId, String targetYearMonth, Schedule.Status status);
}