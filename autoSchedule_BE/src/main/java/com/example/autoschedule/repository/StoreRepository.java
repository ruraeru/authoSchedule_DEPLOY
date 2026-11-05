package com.example.autoschedule.repository;

import com.example.autoschedule.entity.Store;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /** "내 매장" (/stores/me) */
    Optional<Store> findByUserId(Long userId);

    /** 같은 달 DRAFT 교체(삭제 후 생성)를 매장 단위로 줄 세운다. 동시 요청이 DRAFT 를 두 개 만들지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Store> findWithLockByUserId(Long userId);
}