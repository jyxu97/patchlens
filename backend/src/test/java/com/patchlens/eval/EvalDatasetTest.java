package com.patchlens.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDatasetTest {

    @TempDir Path tmp;

    @Test
    void loadsDatasetV2WithExecutableFixturesAndConsistentLabels() throws Exception {
        EvalDataset ds = EvalDataset.load("dataset-v2", tmp.resolve("a"), new ObjectMapper());

        assertThat(ds.version()).isEqualTo("v2");
        assertThat(ds.cases()).extracting(EvalDataset.Case::caseId)
                .containsExactly("auth-001", "cache-001", "payment-001", "pagination-001", "resource-001");

        for (EvalDataset.Case c : ds.cases()) {
            assertThat(c.headDir().resolve("pom.xml")).as(c.caseId()).exists();
            assertThat(c.oracleDir()).as(c.caseId() + " has hidden oracle tests").isNotNull();
            assertThat(c.patchTargets()).as(c.caseId()).hasSize(1);
            assertThat(c.referencePatch()).as(c.caseId()).startsWith("--- a/");

            for (EvalDataset.ExpectedFinding e : c.expectedFindings()) {
                // every label points at real lines of a file that is part of the PR
                assertThat(c.changedFiles()).extracting(cf -> cf.filename()).contains(e.file());
                List<String> lines = Files.readAllLines(c.headDir().resolve(e.file()));
                assertThat(e.lineStart()).as(e.id()).isBetween(1, lines.size());
                assertThat(e.lineEnd()).as(e.id()).isBetween(e.lineStart(), lines.size());
                assertThat(lines.get(e.lineStart() - 1)).as(e.id()).isNotBlank();
            }
            // oracle tests are hidden from the model: not part of the PR diff
            assertThat(c.changedFiles()).noneMatch(cf -> cf.filename().contains("OracleTest"));
        }
    }

    @Test
    void prDiffLineNumbersEqualHeadFileLineNumbers() throws Exception {
        EvalDataset ds = EvalDataset.load("dataset-v2", tmp.resolve("b"), new ObjectMapper());
        var c = ds.cases().get(0);
        var file = c.changedFiles().stream().filter(cf -> cf.filename().endsWith("OAuthCallbackHandler.java")).findFirst().orElseThrow();
        List<String> head = Files.readAllLines(c.headDir().resolve(file.filename()));
        List<String> added = file.patch().lines().skip(1).map(l -> l.substring(1)).toList();
        assertThat(file.status()).isEqualTo("added");
        assertThat(added).isEqualTo(head);
    }

    @Test
    void fingerprintIsStableAcrossLoads() throws Exception {
        String a = EvalDataset.load("dataset-v2", tmp.resolve("c"), new ObjectMapper()).fingerprint();
        String b = EvalDataset.load("dataset-v2", tmp.resolve("d"), new ObjectMapper()).fingerprint();
        assertThat(a).isEqualTo(b).hasSize(16);
    }
}
