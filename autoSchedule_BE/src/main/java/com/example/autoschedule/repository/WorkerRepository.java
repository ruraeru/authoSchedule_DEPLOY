package com.example.autoschedule.repository;

import com.example.autoschedule.entity.Worker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkerRepository extends JpaRepository<Worker, Long> {

    List<Worker> findByStoreIdOrderById(Long storeId);

    Optional<Worker> findByIdAndStoreId(Long id, Long storeId);

    long countByStoreId(Long storeId);
}