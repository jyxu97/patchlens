package com.patchlens.repository;

import com.patchlens.model.EvaluationCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvaluationCaseResultRepository extends JpaRepository<EvaluationCaseResult, UUID> {

    List<EvaluationCaseResult> findByRunId(UUID runId);
}
