package com.patchlens.repository;

import com.patchlens.model.PatchValidation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PatchValidationRepository extends JpaRepository<PatchValidation, UUID> {

    List<PatchValidation> findByPatchId(UUID patchId);

    @Query("SELECT COUNT(v) FROM PatchValidation v WHERE v.patchApplied = true")
    long countPatchApplied();

    @Query("SELECT COUNT(v) FROM PatchValidation v WHERE v.compilePassed = true")
    long countCompilePassed();

    @Query("SELECT COUNT(v) FROM PatchValidation v WHERE v.testsPassed = true")
    long countTestsPassed();

    @Query("SELECT COUNT(v) FROM PatchValidation v")
    long countTotal();
}
