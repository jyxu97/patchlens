package com.patchlens.model;

public record ChangedFile(
        String filename,
        String status,   // added, modified, removed, renamed
        int additions,
        int deletions,
        int changes,
        String patch     // unified diff patch, may be null for binary files
) {}