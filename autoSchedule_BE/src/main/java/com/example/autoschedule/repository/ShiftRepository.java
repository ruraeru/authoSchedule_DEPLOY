package com.example.autoschedule.repository;

import com.example.autoschedule.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ShiftRepository extends JpaRepository<Shift, String> {

    List<Shift> findByScheduleIdOrderByWorkDateAscStartTimeAsc(Long scheduleId);

    List<Shift> findByScheduleIdAndWorkDateBetween(Long scheduleId, LocalDate from, LocalDate to);

    List<Shift> findByScheduleIdAndWorkerIdAndWorkDate(Long scheduleId, Long workerId, LocalDate workDate);

    List<Shift> findByWorkerIdAndWorkDateBetween(Long workerId, LocalDate from, LocalDate to);

    /** 근무표 전체 교체 저장(PUT /schedules/{id}/shifts) 시 기존 블록 일괄 삭제 */
    @Modifying(clearAutomatically = true)
    @Query("delete from Shift s where s.schedule.id = :scheduleId")
    void deleteAllByScheduleId(@Param("scheduleId") Long scheduleId);

    /** 연차 등록 시 해당 날짜의 기존 근무 삭제 */
    @Modifying(clearAutomatically = true)
    @Query("delete from Shift s where s.schedule.id = :scheduleId and s.worker.id = :workerId and s.workDate = :workDate")
    void deleteByScheduleAndWorkerAndDate(@Param("scheduleId") Long scheduleId,
                                          @Param("workerId") Long workerId,
                                          @Param("workDate") LocalDate workDate);

    /** 파트를 지우기 전에 그 파트로 잡힌 근무를 파트 없는 근무로 바꾼다(FK 에 ON DELETE 가 없다). */
    @Modifying
    @Query("update Shift s set s.part = null where s.part.id in :partIds")
    void clearPart(@Param("partIds") Collection<Long> partIds);

    /** 구성원을 지우기 전에 그 사람의 근무·연차를 지운다(FK 에 ON DELETE 가 없다). */
    @Modifying
    @Query("delete from Shift s where s.worker.id = :workerId")
    void deleteByWorkerId(@Param("workerId") Long workerId);
}
