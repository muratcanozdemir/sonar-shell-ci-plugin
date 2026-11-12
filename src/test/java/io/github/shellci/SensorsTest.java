package io.github.shellci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.shellci.ci.CiLanguage;
import io.github.shellci.ci.CiRulesDefinition;
import io.github.shellci.ci.CiSensor;
import io.github.shellci.ci.CiRule;
import io.github.shellci.shell.ShellCheckRunner;
import io.github.shellci.shell.ShellCheckSensor;
import io.github.shellci.shell.ShellLanguage;
import io.github.shellci.shell.ShellRulesDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.fs.InputFile;
import com.sonarsource.scanner.engine.sensor.test.fixtures.TestInputFileBuilder;
import org.sonar.scanner.plugin.api.impl.rule.ActiveRulesBuilder;
import org.sonar.scanner.plugin.api.impl.rule.NewActiveRule;
import com.sonarsource.scanner.engine.sensor.test.fixtures.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;

class SensorsTest {

  @TempDir
  Path baseDir;

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

  private static boolean shellcheckAvailable() {
    return new ShellCheckRunner("shellcheck", 30).version().isPresent();
  }

  private InputFile addFile(SensorContextTester context, String relativePath, String language, String content) throws IOException {
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

  private static SensorContextTester withRules(SensorContextTester context, String repository, Map<String, Map<String, String>> rules) {
    ActiveRulesBuilder builder = new ActiveRulesBuilder();
    rules.forEach((key, params) -> {
      NewActiveRule.Builder rule = new NewActiveRule.Builder().setRuleKey(RuleKey.of(repository, key));
      params.forEach(rule::setParam);
      builder.addRule(rule.build());
    });
    context.setActiveRules(builder.build());
    return context;
  }

  @Test
  void shell_sensor_runs_shellcheck() throws IOException {
    assumeTrue(shellcheckAvailable(), "shellcheck not installed");
    SensorContextTester context = SensorContextTester.create(baseDir);
    withRules(context, ShellRulesDefinition.REPOSITORY, Map.of("SC2086", Map.of(), "SC2164", Map.of()));
    InputFile file = addFile(context, "scripts/build.sh", ShellLanguage.KEY,
      "#!/bin/bash\n# build\n\ncd /tmp\necho $1\n");

    new ShellCheckSensor(FILE_LINES).execute(context);

    Collection<Issue> issues = context.allIssues();
    assertThat(issues).extracting(i -> i.ruleKey().rule()).containsExactlyInAnyOrder("SC2164", "SC2086");
    Issue sc2086 = issues.stream().filter(i -> i.ruleKey().rule().equals("SC2086")).findFirst().orElseThrow();
    assertThat(sc2086.primaryLocation().textRange().start().line()).isEqualTo(5);
    assertThat(sc2086.primaryLocation().textRange().start().lineOffset()).isEqualTo(5);
    assertThat(sc2086.primaryLocation().textRange().end().lineOffset()).isEqualTo(7);
    assertThat(context.measure(file.key(), CoreMetrics.NCLOC).value()).isEqualTo(2);
    assertThat(context.measure(file.key(), CoreMetrics.COMMENT_LINES).value()).isEqualTo(2);
  }

  @Test
  void shell_sensor_imports_reports_and_dedupes(@TempDir Path reports) throws IOException {
    SensorContextTester context = SensorContextTester.create(baseDir);
    withRules(context, ShellRulesDefinition.REPOSITORY, Map.of("SC2086", Map.of()));
    addFile(context, "run.sh", ShellLanguage.KEY, "#!/bin/sh\necho $1\n");
    Path report = reports.resolve("sc.json");
    String comment = "{\"file\":\"run.sh\",\"line\":2,\"endLine\":2,\"column\":6,\"endColumn\":8,\"level\":\"info\",\"code\":2086,\"message\":\"Double quote\"}";
    Files.writeString(report, "{\"comments\":[" + comment + "," + comment
      + ",{\"file\":\"elsewhere.sh\",\"line\":1,\"column\":1,\"level\":\"info\",\"code\":2086,\"message\":\"x\"}"
      + ",{\"file\":\"run.sh\",\"line\":1,\"column\":1,\"level\":\"error\",\"code\":9999,\"message\":\"x\"}]}");
    context.settings().setProperty(Settings.SHELLCHECK_SKIP, "true");
    context.settings().setProperty(Settings.SHELLCHECK_REPORT_PATHS, report.toString());

    new ShellCheckSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).hasSize(1);
    assertThat(context.allIssues().iterator().next().primaryLocation().message()).isEqualTo("Double quote");
  }

  @Test
  void shell_sensor_without_shellcheck_still_saves_metrics() throws IOException {
    SensorContextTester context = SensorContextTester.create(baseDir);
    withRules(context, ShellRulesDefinition.REPOSITORY, Map.of("SC2086", Map.of()));
    context.settings().setProperty(Settings.SHELLCHECK_PATH, "/nonexistent/shellcheck");
    InputFile file = addFile(context, "a.sh", ShellLanguage.KEY, "echo $1\n");

    new ShellCheckSensor(FILE_LINES).execute(context);

    assertThat(context.allIssues()).isEmpty();
    assertThat(context.measure(file.key(), CoreMetrics.NCLOC).value()).isEqualTo(1);
  }

  @Test
  void ghactions_sensor_reports_tree_and_shellcheck_issues(@TempDir Path workDir) throws IOException {
    SensorContextTester context = SensorContextTester.create(baseDir);
    context.fileSystem().setWorkDir(workDir);
    withRules(context, CiRulesDefinition.REPOSITORY, Map.of(
      CiRule.SCRIPT_INJECTION.key(), Map.of(),
      CiRule.UNPINNED_REFERENCE.key(), Map.of(),
      CiRule.RUN_SHELLCHECK.key(), Map.of(CiRule.RunShellCheckParams.MINIMUM_LEVEL, "info")));
    String workflow = String.join("\n",
      "on: issues",
      "jobs:",
      "  triage:",
      "    runs-on: ubuntu-latest",
      "    steps:",
      "      - uses: actions/checkout@v4",
      "      - run: |",
      "          echo \"${{ github.event.issue.title }}\"",
      "          cd $DIR",
      "");
    // indexed as YAML by another plugin: still analyzed, but its measures are left to the YAML analyzer
    InputFile triage = addFile(context, ".github/workflows/triage.yml", "yaml", workflow);
    InputFile broken = addFile(context, ".github/workflows/broken.yml", null, "jobs: [\n");
    addFile(context, "config/other.yml", "yaml", "run: echo \"${{ github.event.issue.title }}\"\n");

    new CiSensor(FILE_LINES).execute(context);

    List<String> found = context.allIssues().stream()
      .map(i -> i.ruleKey().rule() + "@" + i.primaryLocation().textRange().start().line() + ":" + i.primaryLocation().textRange().start().lineOffset())
      .sorted()
      .toList();
    if (shellcheckAvailable()) {
      // SC2164 (cd without || exit) and SC2086 ($DIR unquoted), mapped back into the YAML file
      assertThat(found).containsExactly("RunShellCheck@9:10", "RunShellCheck@9:13", "ScriptInjection@8:16", "UnpinnedReference@6:14");
    } else {
      assertThat(found).containsExactly("ScriptInjection@8:16", "UnpinnedReference@6:14");
    }
    assertThat(context.measure(triage.key(), CoreMetrics.NCLOC)).isNull();
    assertThat(context.measure(broken.key(), CoreMetrics.NCLOC).value()).isEqualTo(1);
  }

  @Test
  void ci_sensor_covers_gitlab_and_azure(@TempDir Path workDir) throws IOException {
    SensorContextTester context = SensorContextTester.create(baseDir);
    context.fileSystem().setWorkDir(workDir);
    withRules(context, CiRulesDefinition.REPOSITORY, Map.of(
      CiRule.HARDCODED_SECRET.key(), Map.of(),
      CiRule.SCRIPT_INJECTION.key(), Map.of(),
      CiRule.RUN_SHELLCHECK.key(), Map.of()));
    addFile(context, ".gitlab-ci.yml", "yaml", String.join("\n",
      "variables:",
      "  API_TOKEN: abcdef123456",
      "lint:",
      "  image: alpine:3.20",
      "  script:",
      "    - if [[ -n $CI ]]; then echo ci; fi",
      ""));
    addFile(context, "azure-pipelines.yml", "yaml", String.join("\n",
      "steps:",
      "  - bash: echo $(Build.SourceBranchName)",
      ""));
    addFile(context, "docs/notes.yml", "yaml", "variables:\n  API_TOKEN: abcdef123456\n");

    new CiSensor(FILE_LINES).execute(context);

    List<String> found = context.allIssues().stream()
      .map(i -> i.primaryLocation().inputComponent().key().replaceFirst("^module:", "") + " " + i.ruleKey().rule() + "@"
        + i.primaryLocation().textRange().start().line())
      .sorted()
      .toList();
    if (shellcheckAvailable()) {
      // SC3010: [[ ]] is not POSIX sh, and alpine has no bash
      assertThat(found).containsExactly(".gitlab-ci.yml HardcodedSecret@2", ".gitlab-ci.yml RunShellCheck@6", "azure-pipelines.yml ScriptInjection@2");
    } else {
      assertThat(found).containsExactly(".gitlab-ci.yml HardcodedSecret@2", "azure-pipelines.yml ScriptInjection@2");
    }
  }
}
