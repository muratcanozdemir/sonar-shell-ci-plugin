package io.github.shellci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sonarsource.scanner.engine.sensor.test.fixtures.SensorContextTester;
import com.sonarsource.scanner.engine.sensor.test.fixtures.TestInputFileBuilder;
import com.sonarsource.scanner.engine.sensor.test.fixtures.TestSensorDescriptorImpl;
import io.github.shellci.ci.CiRule;
import io.github.shellci.ci.CiRulesDefinition;
import io.github.shellci.ci.CiSensor;
import io.github.shellci.ci.checks.UnpinnedReferenceCheck;
import io.github.shellci.shell.ShellCheckRunner;
import io.github.shellci.shell.ShellCheckSensor;
import io.github.shellci.shell.ShellLanguage;
import io.github.shellci.shell.ShellRulesDefinition;
import io.github.shellci.shell.StubShellCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.scanner.plugin.api.impl.rule.ActiveRulesBuilder;
import org.sonar.scanner.plugin.api.impl.rule.NewActiveRule;

class SensorEdgeCasesTest {

  @TempDir
  Path baseDir;
  @TempDir
  Path workDir;
  @TempDir
  Path stubs;

  private static final FileLinesContextFactory FILE_LINES = file -> new FileLinesContext() {
    @Override
    public void setIntValue(String metricKey, int line, int value) {
    }

    @Override
    public void setStringValue(String metricKey, int line, String value) {
    }

    @Override
    public void save() {
    }
  };

  private SensorContextTester context(String repository, Map<String, Map<String, String>> rules) {
    SensorContextTester context = SensorContextTester.create(baseDir);
    context.fileSystem().setWorkDir(workDir);
    ActiveRulesBuilder builder = new ActiveRulesBuilder();
    rules.forEach((key, params) -> {
      NewActiveRule.Builder rule = new NewActiveRule.Builder().setRuleKey(RuleKey.of(repository, key));
      params.forEach(rule::setParam);
      builder.addRule(rule.build());
    });
    context.setActiveRules(builder.build());
    return context;
  }

  private InputFile add(SensorContextTester context, String relativePath, String language, String content) throws IOException {
    Path path = baseDir.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
    InputFile file = TestInputFileBuilder.create("module", baseDir.toFile(), path.toFile())
      .setLanguage(language)
      .setCharset(StandardCharsets.UTF_8)
      .initMetadata(content)
      .build();
    context.fileSystem().add(file);
    return file;
  }

  /** An indexed file whose content cannot be read (deleted after indexing). */
  private InputFile addUnreadable(SensorContextTester context, String relativePath, String language) {
    InputFile file = TestInputFileBuilder.create("module", baseDir.toFile(), baseDir.resolve(relativePath).toFile())
      .setLanguage(language)
      .setCharset(StandardCharsets.UTF_8)
      .setLines(1)
      .build();
    context.fileSystem().add(file);
    return file;
  }

  private static List<String> issues(SensorContextTester context) {
    return context.allIssues().stream()
      .map(i -> i.ruleKey().rule() + "@" + (i.primaryLocation().textRange() == null ? "file" : i.primaryLocation().textRange().start().line()))
      .sorted()
      .toList();
  }

  private static boolean shellcheckAvailable() {
    return new ShellCheckRunner("shellcheck", 30).version().isPresent();
  }

  @Test
  void descriptors() {
    TestSensorDescriptorImpl shell = new TestSensorDescriptorImpl();
    new ShellCheckSensor(FILE_LINES).describe(shell);
    assertThat(shell.name()).isEqualTo("ShellCheck");
    assertThat(shell.languages()).containsExactly(ShellLanguage.KEY);
    assertThat(shell.ruleRepositories()).containsExactly(ShellRulesDefinition.REPOSITORY);
    assertThat(shell.isProcessesHiddenFiles()).isTrue();

    TestSensorDescriptorImpl ci = new TestSensorDescriptorImpl();
    new CiSensor(FILE_LINES).describe(ci);
    assertThat(ci.name()).isEqualTo("CI Pipelines");
    // pipeline files are YAML files: no language restriction
    assertThat(ci.languages()).isEmpty();
    assertThat(ci.ruleRepositories()).containsExactly(CiRulesDefinition.REPOSITORY);
    assertThat(ci.isProcessesHiddenFiles()).isTrue();
  }

  // ---------------------------------------------------------------- ShellCheck sensor

  @Test
  void shell_report_problems_are_logged_not_fatal(@TempDir Path reports) throws IOException {
    SensorContextTester context = context(ShellRulesDefinition.REPOSITORY, Map.of("SC2086", Map.of(), "SC2148", Map.of()));
    context.settings().setProperty(Settings.SHELLCHECK_SKIP, "true");
    add(context, "run.sh", ShellLanguage.KEY, "#!/bin/sh\necho $1\n\n");
    add(context, "notes.txt", null, "echo $1\n");
    Path invalid = reports.resolve("invalid.json");
    Files.writeString(invalid, "{oops");
    Path valid = reports.resolve("valid.json");
    Files.writeString(valid, "[" + String.join(",",
      "{\"file\":\"\",\"line\":1,\"code\":2086}",
      "{\"file\":\"run.sh\",\"line\":3,\"column\":1,\"endColumn\":1,\"code\":2148,\"message\":\"empty line\"}",
      "{\"file\":\"notes.txt\",\"line\":1,\"column\":6,\"endColumn\":8,\"code\":2086,\"message\":\"other language\"}") + "]");
    context.settings().setProperty(Settings.SHELLCHECK_REPORT_PATHS, "missing.json," + invalid + "," + valid);

    new ShellCheckSensor(FILE_LINES).execute(context);

    // the empty-line finding becomes a file-level issue; findings on files outside the shell language are still saved
    assertThat(issues(context)).containsExactly("SC2086@1", "SC2148@file");
  }

  @Test
  void shell_unreadable_files_and_failing_shellcheck() throws IOException {
    assumeTrue(StubShellCheck.posix());
    SensorContextTester context = context(ShellRulesDefinition.REPOSITORY, Map.of("SC2086", Map.of()));
    context.settings().setProperty(Settings.SHELLCHECK_PATH,
      StubShellCheck.create(stubs, "version: 1", "echo crashed >&2; exit 4").toString());
    addUnreadable(context, "gone.sh", ShellLanguage.KEY);
    add(context, "ok.sh", ShellLanguage.KEY, "echo $1\n");

    new ShellCheckSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).isEmpty();
  }

  // ---------------------------------------------------------------- CI sensor

  private static final String GITLAB_SCRIPT = String.join("\n",
    "build:",
    "  script:",
    "    - cd /tmp",
    "    - \"echo \\\"x\\\" && cd /opt\"",
    "");

  @Test
  void ci_skips_unreadable_and_empty_files() throws IOException {
    SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.HARDCODED_SECRET.key(), Map.of()));
    addUnreadable(context, ".gitlab-ci.yml", "yaml");
    add(context, "azure-pipelines.yml", "yaml", "# nothing yet\n");

    new CiSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  void ci_shellcheck_skipped_or_missing() throws IOException {
    for (Map.Entry<String, String> setting : Map.of(
      Settings.SHELLCHECK_SKIP, "true",
      Settings.SHELLCHECK_PATH, "/nonexistent/shellcheck").entrySet()) {
      SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(), Map.of()));
      context.settings().setProperty(setting.getKey(), setting.getValue());
      add(context, ".gitlab-ci.yml", "yaml", GITLAB_SCRIPT);

      new CiSensor(FILE_LINES).execute(context);

      assertThat(context.allIssues()).as(setting.getKey()).isEmpty();
    }
  }

  @Test
  void ci_shellcheck_failures_are_logged() throws IOException {
    assumeTrue(StubShellCheck.posix());
    SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(), Map.of()));
    context.settings().setProperty(Settings.SHELLCHECK_PATH, StubShellCheck.create(stubs, "version: 1", "exit 3").toString());
    add(context, ".gitlab-ci.yml", "yaml", GITLAB_SCRIPT);

    new CiSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  void ci_findings_on_unknown_files_are_ignored() throws IOException {
    assumeTrue(StubShellCheck.posix());
    SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(), Map.of()));
    context.settings().setProperty(Settings.SHELLCHECK_PATH, StubShellCheck.create(stubs, "version: 1",
      "echo '{\"comments\":[{\"file\":\"/elsewhere.sh\",\"line\":1,\"column\":1,\"level\":\"error\",\"code\":1000,\"message\":\"m\"}]}'").toString());
    add(context, ".gitlab-ci.yml", "yaml", GITLAB_SCRIPT);

    new CiSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  void ci_unusable_work_directory() throws IOException {
    SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(), Map.of()));
    Path notADirectory = Files.writeString(workDir.resolve("file"), "x");
    context.fileSystem().setWorkDir(notADirectory);
    assumeTrue(shellcheckAvailable());
    add(context, ".gitlab-ci.yml", "yaml", GITLAB_SCRIPT);

    new CiSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).isEmpty();
  }

  @Test
  void ci_shellcheck_parameters_and_unmappable_columns() throws IOException {
    assumeTrue(shellcheckAvailable());
    String script = String.join("\n",
      "build:",
      "  script:",
      "    - cd /tmp",
      "    - \"echo \\\"x\\\" && cd /opt\"",
      "    - echo $1",
      "");
    // SC2164 (warning) twice, SC2086 (info) once
    SensorContextTester all = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(),
      Map.of(CiRule.RunShellCheckParams.MINIMUM_LEVEL, "info", CiRule.RunShellCheckParams.EXCLUDED_CODES, "")));
    add(all, ".gitlab-ci.yml", "yaml", script);
    new CiSensor(FILE_LINES).execute(all);
    assertThat(issues(all)).containsExactly("RunShellCheck@3", "RunShellCheck@4", "RunShellCheck@5");
    // the quoted scalar has escapes: its finding covers the whole line
    Issue quoted = all.allIssues().stream().filter(i -> i.primaryLocation().textRange().start().line() == 4).findFirst().orElseThrow();
    assertThat(quoted.primaryLocation().textRange().start().lineOffset()).isZero();

    // with default parameters (minimum level "warning") the info-level SC2086 is dropped
    SensorContextTester defaults = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(), Map.of()));
    add(defaults, ".gitlab-ci.yml", "yaml", script);
    new CiSensor(FILE_LINES).execute(defaults);
    assertThat(issues(defaults)).containsExactly("RunShellCheck@3", "RunShellCheck@4");

    // codes can be given with or without the SC prefix
    SensorContextTester excluded = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.RUN_SHELLCHECK.key(),
      Map.of(CiRule.RunShellCheckParams.MINIMUM_LEVEL, "info", CiRule.RunShellCheckParams.EXCLUDED_CODES, "2164, sc2086")));
    add(excluded, ".gitlab-ci.yml", "yaml", script);
    new CiSensor(FILE_LINES).execute(excluded);
    assertThat(excluded.allIssues()).isEmpty();
  }

  @Test
  void ci_rule_parameters_and_secondary_locations() throws IOException {
    SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(
      CiRule.UNPINNED_REFERENCE.key(), Map.of(UnpinnedReferenceCheck.TRUSTED_OWNERS, "trusted-org"),
      CiRule.UNTRUSTED_CHECKOUT.key(), Map.of()));
    add(context, ".github/workflows/pr.yml", "yaml", String.join("\n",
      "on: pull_request_target",
      "jobs:",
      "  a:",
      "    steps:",
      "      - uses: trusted-org/setup@v1",
      "      - uses: other/setup@v1",
      "      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683",
      "        with:",
      "          ref: ${{ github.event.pull_request.head.sha }}",
      ""));

    new CiSensor(FILE_LINES).execute(context);

    assertThat(issues(context)).containsExactly("UnpinnedReference@6", "UntrustedCheckout@9");
    Issue checkout = context.allIssues().stream().filter(i -> i.ruleKey().rule().equals("UntrustedCheckout")).findFirst().orElseThrow();
    assertThat(checkout.flows()).hasSize(1);
    assertThat(checkout.flows().get(0).locations().get(0).textRange().start().line()).isEqualTo(1);
  }

  @Test
  void ci_file_matching_two_platforms_is_analyzed_once() throws IOException {
    SensorContextTester context = context(CiRulesDefinition.REPOSITORY, Map.of(CiRule.HARDCODED_SECRET.key(), Map.of()));
    // matches GitHub's **/action.yml and GitLab's **/.gitlab/ci/**/*.yml
    add(context, ".gitlab/ci/action.yml", "yaml", "variables:\n  API_TOKEN: abcdef123456\n");
    context.settings().setProperty(io.github.shellci.ci.Platform.GITLAB_CI.patternsProperty(), "**/.gitlab/ci/**/*.yml");

    new CiSensor(FILE_LINES).execute(context);

    assertThat(issues(context)).containsExactly("HardcodedSecret@2");
  }
}
