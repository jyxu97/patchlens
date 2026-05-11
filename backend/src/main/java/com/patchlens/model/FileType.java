package com.patchlens.model;

/**
 * Classifies a repository file by its role in the project.
 * Stored as a string in repository_context_chunks.file_type.
 */
public enum FileType {
    DOC,       // README, CONTRIBUTING, docs/**, architecture notes
    CONFIG,    // application.yml, Dockerfile, docker-compose
    SOURCE,    // src/main/**, service/**, controller/**
    API_SPEC,  // openapi.yaml, swagger.yaml, *.proto
    CI,        // .github/workflows/**, Jenkinsfile
    BUILD      // pom.xml, build.gradle, package.json, requirements.txt
}
