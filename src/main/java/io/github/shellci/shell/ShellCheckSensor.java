package io.github.shellci.shell;

import io.github.shellci.Settings;
import io.github.shellci.common.FileContents;
import io.github.shellci.common.LineMetrics;
import io.github.shellci.common.Locations;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;

public class ShellCheckSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(ShellCheckSensor.class);

  private final FileLinesContextFactory fileLinesContextFactory;

  public ShellCheckSensor(FileLinesContextFactory fileLinesContextFactory) {
    this.fileLinesContextFactory = fileLinesContextFactory;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("ShellCheck")
      .onlyOnLanguage(ShellLanguage.KEY)
      .createIssuesForRuleRepository(ShellRulesDefinition.REPOSITORY)
      // Scripts under .github/ (e.g. .github/scripts/*.sh) are indexed as hidden files.
      .processesHiddenFiles();
  }

  @Override
  public void execute(SensorContext context) {
    FileSystem fs = context.fileSystem();
    Map<Path, InputFile> files = new LinkedHashMap<>();
    Map<InputFile, List<String>> contents = new HashMap<>();
    for (InputFile file : fs.inputFiles(fs.predicates().hasLanguage(ShellLanguage.KEY))) {
      files.put(file.path().toAbsolutePath().normalize(), file);
      try {
        List<String> lines = FileContents.lines(file);
        contents.put(file, lines);
        LineMetrics.save(context, fileLinesContextFactory, file, lines);
      } catch (IOException e) {
        LOG.warn("Unable to read {}: {}", file, e.getMessage());
      }
    }

    List<ShellCheckComment> comments = new ArrayList<>();
    boolean skip = Settings.getBoolean(context.config(), Settings.SHELLCHECK_SKIP);
    if (!skip && !files.isEmpty()) {
      comments.addAll(runShellCheck(context, new ArrayList<>(files.keySet())));
    }
    comments.addAll(importReports(context));

    IssueSaver saver = new IssueSaver(context, contents);
    for (ShellCheckComment comment : comments) {
      InputFile file = resolve(fs, files, comment.file());
      if (file == null) {
        LOG.debug("Skipping ShellCheck {} on '{}': file is not part of the analysis", comment.ruleKey(), comment.file());
        continue;
      }
      saver.save(file, comment);
    }
    saver.logSummary();
  }

  private static List<ShellCheckComment> runShellCheck(SensorContext context, List<Path> paths) {
    ShellCheckRunner runner = runner(context);
    Optional<String> version = runner.version();
    if (version.isEmpty()) {
      LOG.warn("ShellCheck executable '{}' could not be run. Shell scripts get metrics only. Install ShellCheck, set {}, or import reports with {}.",
        Settings.get(context.config(), Settings.SHELLCHECK_PATH).orElse(Settings.SHELLCHECK_PATH_DEFAULT),
        Settings.SHELLCHECK_PATH, Settings.SHELLCHECK_REPORT_PATHS);
      return List.of();
    }
    LOG.info("Running ShellCheck {} on {} file(s)", version.get(), paths.size());
    try {
      return runner.analyze(paths, null);
    } catch (IOException e) {
      LOG.error("ShellCheck analysis failed: {}", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return List.of();
  }

  public static ShellCheckRunner runner(SensorContext context) {
    String executable = Settings.get(context.config(), Settings.SHELLCHECK_PATH).orElse(Settings.SHELLCHECK_PATH_DEFAULT);
    long timeout = context.config().getLong(Settings.SHELLCHECK_TIMEOUT).orElse(300L);
    return new ShellCheckRunner(executable, timeout);
  }

  private static List<ShellCheckComment> importReports(SensorContext context) {
    List<ShellCheckComment> comments = new ArrayList<>();
    for (String reportPath : context.config().getStringArray(Settings.SHELLCHECK_REPORT_PATHS)) {
      Path path = context.fileSystem().baseDir().toPath().resolve(reportPath.trim()).normalize();
      if (!Files.isRegularFile(path)) {
        LOG.warn("ShellCheck report not found: {}", path);
        continue;
      }
      try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        List<ShellCheckComment> imported = ShellCheckReportParser.parse(reader);
        LOG.info("Imported {} finding(s) from ShellCheck report {}", imported.size(), path);
        comments.addAll(imported);
      } catch (IOException | IllegalArgumentException e) {
        LOG.error("Unable to import ShellCheck report {}: {}", path, e.getMessage());
      }
    }
    return comments;
  }

  private static InputFile resolve(FileSystem fs, Map<Path, InputFile> byPath, String reported) {
    if (reported == null || reported.isEmpty()) {
      return null;
    }
    Path path = fs.baseDir().toPath().resolve(reported).toAbsolutePath().normalize();
    InputFile file = byPath.get(path);
    return file != null ? file : fs.inputFile(fs.predicates().hasAbsolutePath(path.toString()));
  }

  /** Converts comments into issues for active rules, dropping duplicates (run + imported report). */
  static final class IssueSaver {
    private final SensorContext context;
    private final Map<InputFile, List<String>> contents;
    private final Set<String> seen = new HashSet<>();
    private final Set<String> unknownRules = new HashSet<>();
    private int saved;

    IssueSaver(SensorContext context, Map<InputFile, List<String>> contents) {
      this.context = context;
      this.contents = contents;
    }

    void save(InputFile file, ShellCheckComment c) {
      RuleKey ruleKey = RuleKey.of(ShellRulesDefinition.REPOSITORY, c.ruleKey());
      if (context.activeRules().find(ruleKey) == null) {
        if (!ShellCheckCatalog.contains(c.ruleKey())) {
          unknownRules.add(c.ruleKey());
        }
        return;
      }
      if (!seen.add(file.key() + ':' + c.line() + ':' + c.column() + ':' + c.code())) {
        return;
      }
      List<String> lines = contents.computeIfAbsent(file, f -> {
        try {
          return FileContents.lines(f);
        } catch (IOException e) {
          return List.of();
        }
      });
      NewIssue issue = context.newIssue().forRule(ruleKey);
      NewIssueLocation location = issue.newLocation().on(file).message(c.message());
      TextRange range = Locations.range(file, lines, c.line(), c.column() - 1, c.endLine(), c.endColumn() - 1);
      if (range != null) {
        location.at(range);
      }
      issue.at(location).save();
      saved++;
    }

    void logSummary() {
      LOG.info("ShellCheck: {} issue(s) saved", saved);
      if (!unknownRules.isEmpty()) {
        LOG.warn("ShellCheck reported checks unknown to this plugin version, ignored: {}", unknownRules);
      }
    }
  }
}
