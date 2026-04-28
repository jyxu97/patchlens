package com.patchlens.repository;

import com.patchlens.model.ReviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for ReviewSession.
 * Spring auto-generates the implementation at startup — no SQL needed for basic CRUD.
 */
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, UUID> {}
