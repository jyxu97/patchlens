package com.patchlens.repository;

import com.patchlens.model.PatchSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PatchSuggestionRepository extends JpaRepository<PatchSuggestion, UUID> {

    List<PatchSuggestion> findByFindingId(UUID findingId);

    @Query("SELECT COUNT(p) FROM PatchSuggestion p")
    long countTotal();

    @Query("SELECT COUNT(p) FROM PatchSuggestion p WHERE p.status = com.patchlens.model.ValidationStatus.VALIDATED")
    long countValidated();

    @Query("SELECT COALESCE(AVG(p.repairAttempts), 0) FROM PatchSuggestion p WHERE p.repairAttempts > 0")
    double avgRepairAttempts();
}
