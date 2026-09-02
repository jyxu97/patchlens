package com.patchlens.ai.dto;

/** A single evidence citation returned by the review model. */
public record EvidenceRef(String file, int line, String snippet) {}
