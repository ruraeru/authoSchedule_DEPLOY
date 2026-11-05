package com.example.autoschedule.repository;

import com.example.autoschedule.entity.WorkerAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WorkerAvailabilityRepository extends JpaRepository<WorkerAvailability, Long> {

    List<WorkerAvailability> findByWorkerId(Long workerId);

    /** AI 배치 시 매장 전체 구성원의 가능 시간을 한 번에 조회 */
    List<WorkerAvailability> findByWorkerIdIn(Collection<Long> workerIds);

    /** 파트를 지우기 전에 그 파트를 가리키는 가능 파트 행을 지운다(FK 에 ON DELETE 가 없다). */
    @Modifying
    @Query("delete from WorkerAvailability a where a.part.id in :partIds")
    void deleteByPartIdIn(@Param("partIds") Collection<Long> partIds);
}