package com.example.autoschedule.repository;

import com.example.autoschedule.entity.Part;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long> {

    List<Part> findByStoreIdOrderByStartTime(Long storeId);

    Optional<Part> findByIdAndStoreId(Long id, Long storeId);
}