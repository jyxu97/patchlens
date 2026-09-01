package com.patchlens.repository;

import com.patchlens.model.EvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, UUID> {
}
