package com.example.autoschedule.repository;

import com.example.autoschedule.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    List<Template> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    Optional<Template> findByIdAndStoreId(Long id, Long storeId);
}