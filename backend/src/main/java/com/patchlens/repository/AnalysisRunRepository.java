package com.patchlens.repository;

import com.patchlens.model.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {}
