package io.github.shellci.ci;

import io.github.shellci.Settings;
import io.github.shellci.common.FileContents;
import io.github.shellci.common.LineMetrics;
import io.github.shellci.common.Locations;
import io.github.shellci.ci.checks.CiCheck;
import io.github.shellci.ci.model.Span;
import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.scripts.ScriptExtractor;
import io.github.shellci.shell.ShellCheckComment;
import io.github.shellci.shell.ShellCheckRunner;
import io.github.shellci.shell.ShellCheckSensor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;

public class CiSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(CiSensor.class);
  private static final List<String> LEVELS = List.of("style", "info", "warning", "error");

  private final FileLinesContextFactory fileLinesContextFactory;

  public CiSensor(FileLinesContextFactory fileLinesContextFactory) {
    this.fileLinesContextFactory = fileLinesContextFactory;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("CI Pipelines")
      .createIssuesForRuleRepository(CiRulesDefinition.REPOSITORY)
      .processesHiddenFiles();
  }

  @Override
  public void execute(SensorContext context) {
    FileSystem fs = context.fileSystem();
    Map<CiRule, ActiveRule> active = new HashMap<>();
    for (CiRule rule : CiRule.values()) {
      ActiveRule activeRule = context.activeRules().find(RuleKey.of(CiRulesDefinition.REPOSITORY, rule.key()));
      if (activeRule != null) {
        active.put(rule, activeRule);
      }
    }

    List<PendingScript> scripts = new ArrayList<>();
    Set<InputFile> seen = new HashSet<>();
    Map<Platform, Integer> parsed = new EnumMap<>(Platform.class);
    for (Platform platform : Platform.values()) {
      for (InputFile file : fs.inputFiles(fs.predicates().matchesPathPatterns(patterns(context, platform)))) {
        if (seen.add(file)) {
          if (analyze(context, file, platform, active, scripts)) {
            parsed.merge(platform, 1, Integer::sum);
          }
        }
      }
    }
    if (!parsed.isEmpty()) {
      LOG.info("CI Pipelines: analyzed {}", parsed.entrySet().stream()
        .map(e -> e.getValue() + " " + e.getKey().displayName() + " file(s)")
        .collect(Collectors.joining(", ")));
    }

    if (!scripts.isEmpty()) {
      runShellCheck(context, scripts, active.get(CiRule.RUN_SHELLCHECK));
    }
  }

  static String[] patterns(SensorContext context, Platform platform) {
    String[] patterns = Arrays.stream(context.config().getStringArray(platform.patternsProperty()))
      .map(String::trim)
      .filter(p -> !p.isEmpty())
      .toArray(String[]::new);
    return patterns.length == 0 ? platform.defaultPatterns().split(",") : patterns;
  }

  /** Returns false when the file could not be read or parsed. */
  private boolean analyze(SensorContext context, InputFile file, Platform platform, Map<CiRule, ActiveRule> active,
    List<PendingScript> scripts) {
    String text;
    try {
      text = file.contents();
    } catch (IOException e) {
      LOG.warn("Unable to read {}: {}", file, e.getMessage());
      return false;
    }
    CiFile pipeline;
    try {
      pipeline = CiFile.parse(text, platform);
    } catch (CiFile.ParseException e) {
      LOG.warn("Unable to parse {} at line {}: {}", file, e.line(), e.getMessage());
      saveMetrics(context, file, FileContents.lines(text));
      return false;
    }
    saveMetrics(context, file, pipeline.lines());
    if (pipeline.root() == null) {
      return true;
    }
    for (Map.Entry<CiRule, ActiveRule> entry : active.entrySet()) {
      CiCheck check = entry.getKey().newCheck();
      if (check == null || !entry.getKey().platforms().contains(platform)) {
        continue;
      }
      try {
        check.check(pipeline, new CheckContext(context, file, pipeline, entry.getKey(), entry.getValue()));
      } catch (RuntimeException e) {
        LOG.error("Rule {} failed on {}: {}", entry.getKey().key(), file, e.toString());
        LOG.debug("Stack trace", e);
      }
    }
    if (active.containsKey(CiRule.RUN_SHELLCHECK)) {
      ScriptExtractor.extractShellScripts(pipeline).forEach(s -> scripts.add(new PendingScript(file, pipeline, s)));
    }
    return true;
  }

  /** Only for files no other language owns: their analyzer may already save the same measures, which would fail. */
  private void saveMetrics(SensorContext context, InputFile file, List<String> lines) {
    String language = file.language();
    if (language == null || CiLanguage.KEY.equals(language)) {
      LineMetrics.save(context, fileLinesContextFactory, file, lines);
    }
  }

  private record PendingScript(InputFile file, CiFile workflow, ScriptExtractor.RunScript script) {
  }

  private static void runShellCheck(SensorContext context, List<PendingScript> scripts, ActiveRule rule) {
    if (Settings.getBoolean(context.config(), Settings.SHELLCHECK_SKIP)) {
      return;
    }
    ShellCheckRunner runner = ShellCheckSensor.runner(context);
    if (runner.version().isEmpty()) {
      LOG.warn("ShellCheck is not available: {} script(s) in CI pipeline files are not checked.", scripts.size());
      return;
    }
    int minLevel = Math.max(0, LEVELS.indexOf(param(rule, CiRule.RunShellCheckParams.MINIMUM_LEVEL).trim().toLowerCase(Locale.ROOT)));
    Set<String> excluded = Arrays.stream(param(rule, CiRule.RunShellCheckParams.EXCLUDED_CODES).split(","))
      .map(s -> s.trim().toUpperCase(Locale.ROOT))
      .map(s -> s.startsWith("SC") ? s : "SC" + s)
      .collect(Collectors.toSet());

    Path dir;
    try {
      dir = Files.createDirectories(context.fileSystem().workDir().toPath().resolve("shell-ci-run-scripts"));
    } catch (IOException e) {
      LOG.error("Unable to create a work directory for run scripts: {}", e.getMessage());
      return;
    }
    Map<String, List<Path>> byDialect = new HashMap<>();
    Map<Path, PendingScript> byPath = new HashMap<>();
    for (int i = 0; i < scripts.size(); i++) {
      PendingScript pending = scripts.get(i);
      Path path = dir.resolve("run-" + i + "." + pending.script().dialect()).toAbsolutePath().normalize();
      try {
        Files.writeString(path, pending.script().script(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        LOG.warn("Unable to write run script for {}: {}", pending.file(), e.getMessage());
        continue;
      }
      byDialect.computeIfAbsent(pending.script().dialect(), d -> new ArrayList<>()).add(path);
      byPath.put(path, pending);
    }

    int saved = 0;
    for (Map.Entry<String, List<Path>> group : byDialect.entrySet()) {
      List<ShellCheckComment> comments;
      try {
        comments = runner.analyze(group.getValue(), group.getKey());
      } catch (IOException e) {
        LOG.error("ShellCheck failed on run scripts: {}", e.getMessage());
        continue;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      for (ShellCheckComment c : comments) {
        PendingScript pending = byPath.get(Path.of(c.file()).toAbsolutePath().normalize());
        if (pending == null || excluded.contains(c.ruleKey()) || LEVELS.indexOf(c.level()) < minLevel) {
          continue;
        }
        saveRunIssue(context, pending, c);
        saved++;
      }
    }
    LOG.info("CI Pipelines: checked {} script(s) with ShellCheck, {} issue(s)", scripts.size(), saved);
  }

  private static void saveRunIssue(SensorContext context, PendingScript pending, ShellCheckComment c) {
    ScriptExtractor.RunScript script = pending.script();
    int line = script.sourceLine(c.line());
    int endLine = script.sourceLine(c.endLine());
    int column = script.sourceColumn(c.line(), c.column());
    int endColumn = script.sourceColumn(c.endLine(), c.endColumn());
    List<String> lines = pending.workflow().lines();
    TextRange range;
    if (column < 0 || endColumn < 0) {
      range = Locations.range(pending.file(), lines, line, 0, line, pending.workflow().line(line).length());
    } else {
      range = Locations.range(pending.file(), lines, line, column, endLine, endColumn);
    }
    NewIssue issue = context.newIssue().forRule(RuleKey.of(CiRulesDefinition.REPOSITORY, CiRule.RUN_SHELLCHECK.key()));
    NewIssueLocation location = issue.newLocation().on(pending.file()).message(c.ruleKey() + ": " + c.message());
    if (range != null) {
      location.at(range);
    }
    issue.at(location).save();
  }

  private static String param(ActiveRule rule, String key) {
    String value = rule.param(key);
    return value != null ? value : Optional.ofNullable(CiRule.RUN_SHELLCHECK.params().get(key)).map(CiRule.Param::defaultValue).orElse("");
  }

  private static final class CheckContext implements CiCheck.Context {
    private final SensorContext context;
    private final InputFile file;
    private final CiFile workflow;
    private final CiRule rule;
    private final ActiveRule activeRule;

    CheckContext(SensorContext context, InputFile file, CiFile workflow, CiRule rule, ActiveRule activeRule) {
      this.context = context;
      this.file = file;
      this.workflow = workflow;
      this.rule = rule;
      this.activeRule = activeRule;
    }

    @Override
    public void report(Span primary, String message, List<CiCheck.Secondary> secondaries) {
      NewIssue issue = context.newIssue().forRule(activeRule.ruleKey());
      issue.at(location(issue, primary, message));
      for (CiCheck.Secondary secondary : secondaries) {
        issue.addLocation(location(issue, secondary.span(), secondary.message()));
      }
      issue.save();
    }

    private NewIssueLocation location(NewIssue issue, Span span, String message) {
      NewIssueLocation location = issue.newLocation().on(file).message(message);
      TextRange range = Locations.range(file, workflow.lines(), span.line(), span.column(), span.endLine(), span.endColumn());
      if (range != null) {
        location.at(range);
      }
      return location;
    }

    @Override
    public String param(String key) {
      String value = activeRule.param(key);
      if (value != null) {
        return value;
      }
      CiRule.Param param = rule.params().get(key);
      return param == null ? "" : param.defaultValue();
    }
  }
}
