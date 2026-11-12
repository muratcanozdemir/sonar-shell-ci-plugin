package io.github.shellci.ci.scripts;

import io.github.shellci.ci.model.Azure;
import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.GitLab;
import io.github.shellci.ci.model.ScriptNode;
import io.github.shellci.ci.model.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** Turns the scripts embedded in pipeline YAML into standalone script text that maps back to YAML positions. */
public final class ScriptExtractor {

  /**
   * A script plus, for each of its lines (index 0 = script line 1), the 1-based source line and the source column where
   * the script line starts, or -1 when columns cannot be mapped exactly.
   */
  public record RunScript(String dialect, String script, List<Integer> sourceLines, List<Integer> columnOffsets) {

    public RunScript {
      sourceLines = List.copyOf(sourceLines);
      columnOffsets = List.copyOf(columnOffsets);
    }

    /** Maps a 1-based script line and 1-based column to a 0-based source column, or -1 if unknown. */
    public int sourceColumn(int scriptLine, int scriptColumn) {
      int offset = columnOffsets.get(clampLine(scriptLine) - 1);
      return offset < 0 ? -1 : offset + scriptColumn - 1;
    }

    public int sourceLine(int scriptLine) {
      return sourceLines.get(clampLine(scriptLine) - 1);
    }

    public List<String> scriptLines() {
      return List.of(script.split("\n", -1)).subList(0, sourceLines.size());
    }

    private int clampLine(int scriptLine) {
      return Math.max(1, Math.min(scriptLine, sourceLines.size()));
    }
  }

  private ScriptExtractor() {
  }

  /** Every script of the file, whatever shell runs it. */
  public static List<ScriptNode> scriptNodes(CiFile file) {
    if (file.root() == null) {
      return List.of();
    }
    return switch (file.platform()) {
      case GITHUB_ACTIONS -> GitHub.scripts(file);
      case GITLAB_CI -> GitLab.scripts(file);
      case AZURE_PIPELINES -> Azure.steps(file).stream().flatMap(step -> Azure.scripts(step).stream()).toList();
    };
  }

  /** Scripts run by bash or sh, ready for ShellCheck. */
  public static List<RunScript> extractShellScripts(CiFile file) {
    List<RunScript> scripts = new ArrayList<>();
    for (ScriptNode node : scriptNodes(file)) {
      if (node.dialect() != null) {
        toScript(file, node).ifPresent(scripts::add);
      }
    }
    return scripts;
  }

  /**
   * Builds the script text. Literal block ({@code |}) and single-line scalars map line-for-line onto the source;
   * folded ({@code >}) and multi-line flow scalars do not and are left out.
   */
  public static Optional<RunScript> toScript(CiFile file, ScriptNode node) {
    List<String> lines = new ArrayList<>();
    List<Integer> sourceLines = new ArrayList<>();
    List<Integer> offsets = new ArrayList<>();
    for (ScalarNode scalar : node.parts()) {
      appendPart(file, scalar, lines, sourceLines, offsets);
    }
    if (lines.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new RunScript(node.dialect(), mask(lines, file.platform().expressions()), sourceLines, offsets));
  }

  private static void appendPart(CiFile file, ScalarNode scalar, List<String> lines, List<Integer> sourceLines, List<Integer> offsets) {
    Span span = file.span(scalar);
    String value = scalar.getValue();
    if (scalar.getScalarStyle() == ScalarStyle.LITERAL) {
      List<String> scriptLines = new ArrayList<>(List.of(value.split("\n", -1)));
      if (scriptLines.size() > 1 && scriptLines.get(scriptLines.size() - 1).isEmpty()) {
        scriptLines.remove(scriptLines.size() - 1);
      }
      for (int i = 0; i < scriptLines.size(); i++) {
        int sourceLine = span.line() + 1 + i; // content starts on the line after the "|" indicator
        String source = file.line(sourceLine);
        String script = scriptLines.get(i);
        lines.add(script);
        sourceLines.add(sourceLine);
        offsets.add(script.isEmpty() ? 0 : (source.endsWith(script) ? source.length() - script.length() : -1));
      }
    } else if (span.line() == span.endLine() && !value.contains("\n")) {
      int offset = span.column() + (scalar.getScalarStyle() == ScalarStyle.PLAIN ? 0 : 1);
      String source = file.line(span.line());
      boolean exact = source.startsWith(value, Math.min(offset, source.length()));
      lines.add(value);
      sourceLines.add(span.line());
      offsets.add(exact ? offset : -1);
    }
  }

  /** Replaces template expressions with same-length filler so shells see a plain word and columns stay aligned. */
  static String mask(List<String> lines, List<Pattern> expressions) {
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      String masked = line;
      for (Pattern expression : expressions) {
        Matcher m = expression.matcher(masked);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
          out.append(masked, last, m.start()).append("_".repeat(m.end() - m.start()));
          last = m.end();
        }
        masked = out.append(masked.substring(last)).toString();
      }
      sb.append(masked).append('\n');
    }
    return sb.toString();
  }
}
