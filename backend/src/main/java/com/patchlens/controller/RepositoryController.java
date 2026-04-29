package com.patchlens.controller;

import com.patchlens.service.ContextIndexingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final ContextIndexingService indexingService;

    public RepositoryController(ContextIndexingService indexingService) {
        this.indexingService = indexingService;
    }

    /**
     * Indexes a set of files from a GitHub repository for RAG context retrieval.
     * POST /api/repositories/index
     * Body: { "owner": "...", "repo": "...", "files": ["README.md", "docs/arch.md"] }
     *
     * This is an admin/dev endpoint — call it once before analyzing PRs
     * to populate the context chunks used in AI prompt generation.
     */
    @PostMapping("/index")
    public ResponseEntity<?> index(@Valid @RequestBody IndexRequest request) {
        int chunksIndexed = indexingService.indexFiles(
                request.owner(), request.repo(), request.files()
        );
        return ResponseEntity.ok(Map.of(
                "repository", request.owner() + "/" + request.repo(),
                "filesRequested", request.files().size(),
                "chunksIndexed", chunksIndexed
        ));
    }

    record IndexRequest(
            @NotBlank String owner,
            @NotBlank String repo,
            @NotEmpty List<String> files
    ) {}
}