package com.patchlens.service;

/**
 * Configuration loaded from {@code patchlens.yaml} in the target repository root.
 * Describes how to build and test the project inside the Docker sandbox.
 */
public class ValidationConfig {

    private String language = "java";
    private Build build = new Build();
    private Tests tests = new Tests();
    private Timeouts timeouts = new Timeouts();

    public record Build(String command) {
        public Build() { this("./mvnw -q -DskipTests test-compile"); }
    }

    public record Tests(String command) {
        public Tests() { this("./mvnw -q test"); }
    }

    public record Timeouts(int compileSeconds, int testSeconds) {
        public Timeouts() { this(120, 300); }
    }

    public ValidationConfig() {}

    public ValidationConfig(String language, Build build, Tests tests, Timeouts timeouts) {
        this.language = language;
        this.build = build;
        this.tests = tests;
        this.timeouts = timeouts;
    }

    public static ValidationConfig defaults() {
        return new ValidationConfig("java", new Build(), new Tests(), new Timeouts());
    }

    public String getLanguage()    { return language; }
    public Build getBuild()        { return build; }
    public Tests getTests()        { return tests; }
    public Timeouts getTimeouts()  { return timeouts; }

    public void setLanguage(String language)    { this.language = language; }
    public void setBuild(Build build)           { this.build = build; }
    public void setTests(Tests tests)           { this.tests = tests; }
    public void setTimeouts(Timeouts timeouts)  { this.timeouts = timeouts; }
}
