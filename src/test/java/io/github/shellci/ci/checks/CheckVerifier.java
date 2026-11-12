package io.github.shellci.ci.checks;

import io.github.shellci.Fixtures;
import io.github.shellci.ci.Platform;
import io.github.shellci.ci.model.Span;
import io.github.shellci.ci.model.CiFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs a check on a fixture (see {@link Fixtures}) and records what it reports. */
final class CheckVerifier {

  record Issue(Span span, String message, List<CiCheck.Secondary> secondaries, String text) {
    int line() {
      return span.line();
    }
  }

  private CheckVerifier() {
  }

  static List<Issue> run(CiCheck check, String fixture) {
    return run(check, fixture, Map.of());
  }

  static List<Issue> run(CiCheck check, String fixture, Map<String, String> params) {
    return runText(check, Fixtures.read(fixture), Fixtures.platformOf(fixture), params);
  }

  static List<Issue> runText(CiCheck check, String text, Platform platform) {
    return runText(check, text, platform, Map.of());
  }

  static List<Issue> runText(CiCheck check, String text, Platform platform, Map<String, String> params) {
    CiFile file = CiFile.parse(text, platform);
    List<Issue> issues = new ArrayList<>();
    check.check(file, new CiCheck.Context() {
      @Override
      public void report(Span primary, String message, List<CiCheck.Secondary> secondaries) {
        String line = file.line(primary.line());
        String covered = primary.line() == primary.endLine()
          ? line.substring(primary.column(), Math.min(primary.endColumn(), line.length()))
          : line.substring(primary.column());
        issues.add(new Issue(primary, message, secondaries, covered));
      }

      @Override
      public String param(String key) {
        return params.getOrDefault(key, "");
      }
    });
    return issues;
  }

  static List<Integer> lines(List<Issue> issues) {
    return issues.stream().map(Issue::line).toList();
  }
}
