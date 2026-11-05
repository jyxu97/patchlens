package com.patchlens.service;

/**
 * Describes how to build and test a repository inside the Docker sandbox.
 *
 * <p>Defaults target a plain Maven project. A repository may override them with a
 * {@code patchlens.yaml} (or {@code .patchlens.yml}) file at its root — see
 * {@link com.patchlens.sandbox.ValidationConfigLoader}. Commands run <em>inside</em> the
 * sandbox container (network disabled, non-root), never on the host.
 *
 * <p>Maven commands should not include {@code -o}/{@code -B}/{@code -Dmaven.repo.local}: the
 * sandbox injects offline mode and the dependency-cache layout through {@code MAVEN_ARGS}.
 */
public class ValidationConfig {

    private String language = "java";
    private String buildFile = "pom.xml";
    private Build build = new Build();
    private StaticAnalysis staticAnalysis = new StaticAnalysis();
    private Tests tests = new Tests();
    private Timeouts timeouts = new Timeouts();

    public record Build(String command) {
        public Build() { this("mvn -q -DskipTests test-compile"); }
    }

    /**
     * Optional static-analysis step. Disabled by default: most repositories have no checkstyle
     * configuration, and running one with default rules would reject nearly every patch.
     */
    public record StaticAnalysis(String command) {
        public StaticAnalysis() { this(""); }
        /** Returns false if the command is blank — the step does not run. */
        public boolean isEnabled() { return command != null && !command.isBlank(); }
    }

    public record Tests(String command) {
        public Tests() { this("mvn -q test"); }
    }

    public record Timeouts(int compileSeconds, int staticAnalysisSeconds, int testSeconds) {
        public Timeouts() { this(300, 120, 300); }
    }

    public ValidationConfig() {}

    public ValidationConfig(String language, Build build, StaticAnalysis staticAnalysis,
                            Tests tests, Timeouts timeouts) {
        this.language = language;
        this.build = build;
        this.staticAnalysis = staticAnalysis;
        this.tests = tests;
        this.timeouts = timeouts;
    }

    public static ValidationConfig defaults() {
        return new ValidationConfig(
                "java", new Build(), new StaticAnalysis(), new Tests(), new Timeouts());
    }

    public String getLanguage()              { return language; }
    public String getBuildFile()             { return buildFile; }
    public Build getBuild()                  { return build; }
    public StaticAnalysis getStaticAnalysis() { return staticAnalysis; }
    public Tests getTests()                  { return tests; }
    public Timeouts getTimeouts()            { return timeouts; }

    public void setLanguage(String language)               { this.language = language; }
    public void setBuildFile(String buildFile)             { this.buildFile = buildFile; }
    public void setBuild(Build build)                      { this.build = build; }
    public void setStaticAnalysis(StaticAnalysis sa)       { this.staticAnalysis = sa; }
    public void setTests(Tests tests)                      { this.tests = tests; }
    public void setTimeouts(Timeouts timeouts)              { this.timeouts = timeouts; }
}
