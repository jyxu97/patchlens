package com.patchlens.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads sample PR fixtures from src/main/resources/fixtures/samples/.
 */
@Component
public class SamplePrLoader {

    private final ObjectMapper objectMapper;

    public SamplePrLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record SamplePr(PullRequestMetadata metadata, List<ChangedFile> files) {}

    /**
     * Loads a sample PR by its ID (e.g. "redis-session-cache").
     *
     * @throws IllegalArgumentException if the sample ID is not found
     */
    public SamplePr load(String sampleId) {
        String path = "fixtures/samples/" + sampleId + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Sample PR not found: " + sampleId);
        }
        // try-with-resources: InputStream is closed automatically after the block
        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is); // parse JSON into a traversable tree

            JsonNode meta = root.get("metadata");
            PullRequestMetadata metadata = new PullRequestMetadata(
                    meta.get("owner").asString(),
                    meta.get("repo").asString(),
                    meta.get("pullNumber").asInt(),
                    meta.get("title").asString(),
                    meta.get("body").asString(""),
                    meta.get("url").asString("")
            );

            List<ChangedFile> files = new ArrayList<>();
            for (JsonNode fileNode : root.get("files")) {
                files.add(new ChangedFile(
                        fileNode.get("filename").asString(),
                        fileNode.get("status").asString(),
                        fileNode.get("additions").asInt(),
                        fileNode.get("deletions").asInt(),
                        fileNode.get("changes").asInt(),
                        // patch may be absent for binary files
                        fileNode.has("patch") ? fileNode.get("patch").asString(null) : null
                ));
            }

            return new SamplePr(metadata, files);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sample PR: " + sampleId, e);
        }
    }
}