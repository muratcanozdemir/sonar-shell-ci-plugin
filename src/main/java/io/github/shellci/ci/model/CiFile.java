package io.github.shellci.ci.model;

import io.github.shellci.ci.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** A parsed CI pipeline file: the YAML tree plus the source lines, to turn nodes into precise source ranges. */
public final class CiFile {

  public record Match(Span span, String text) {
  }

  private final Platform platform;
  private final List<String> lines;
  private final Node root;

  private CiFile(Platform platform, List<String> lines, Node root) {
    this.platform = platform;
    this.lines = lines;
    this.root = root;
  }

  /**
   * Parses YAML text. Throws {@link ParseException} with a 1-based line when the document is not valid YAML. An empty
   * document yields a file whose {@link #root()} is null. With several documents (GitLab CI/CD components put a
   * {@code spec:} header document first) the last one is the pipeline.
   */
  public static CiFile parse(String text, Platform platform) {
    List<String> lines = List.of(text.split("\r\n|\r|\n", -1));
    try {
      Node root = null;
      // Non-scalar keys ("? [a, b]: x") are valid YAML; navigation helpers treat them as unnamed keys.
      LoadSettings settings = LoadSettings.builder().setLabel(platform.displayName()).setAllowNonScalarKeys(true).build();
      for (Node document : new Compose(settings).composeAllFromString(text)) {
        if (document != null) {
          root = document;
        }
      }
      return new CiFile(platform, lines, root);
    } catch (MarkedYamlEngineException e) {
      int line = e.getProblemMark().map(m -> m.getLine() + 1).orElse(1);
      throw new ParseException(line, e.getProblem() == null ? e.getMessage() : e.getProblem(), e);
    } catch (RuntimeException e) {
      throw new ParseException(1, e.getMessage(), e);
    }
  }

  public static final class ParseException extends RuntimeException {
    private final int line;

    ParseException(int line, String message, Throwable cause) {
      super(message, cause);
      this.line = line;
    }

    public int line() {
      return line;
    }
  }

  public Platform platform() {
    return platform;
  }

  /** The document's root node, or null for an empty document. */
  public Node root() {
    return root;
  }

  public List<String> lines() {
    return lines;
  }

  public String line(int oneBasedLine) {
    return oneBasedLine >= 1 && oneBasedLine <= lines.size() ? lines.get(oneBasedLine - 1) : "";
  }

  public Span span(Node node) {
    Mark start = node.getStartMark().orElseThrow();
    Mark end = node.getEndMark().orElse(start);
    int startLine = start.getLine() + 1;
    int startCol = toCharColumn(start.getLine(), start.getColumn());
    int endLine = end.getLine() + 1;
    int endCol = toCharColumn(end.getLine(), end.getColumn());
    // Block scalars end at column 0 of the following line: pull the end back to the last content line.
    while (endLine > startLine && endCol == 0) {
      endLine--;
      endCol = line(endLine).length();
    }
    if (endLine == startLine && endCol <= startCol) {
      endCol = Math.max(startCol + 1, line(startLine).length());
    }
    return new Span(startLine, startCol, endLine, endCol);
  }

  /** Finds regex matches inside the raw source text of a scalar node. Matches never span lines. */
  public List<Match> find(ScalarNode node, Pattern pattern) {
    List<Match> matches = new ArrayList<>();
    Span span = span(node);
    for (int lineNo = span.line(); lineNo <= span.endLine(); lineNo++) {
      String text = line(lineNo);
      int from = lineNo == span.line() ? Math.min(span.column(), text.length()) : 0;
      int to = lineNo == span.endLine() ? Math.min(Math.max(span.endColumn(), from), text.length()) : text.length();
      Matcher m = pattern.matcher(text).region(from, to);
      while (m.find()) {
        matches.add(new Match(new Span(lineNo, m.start(), lineNo, m.end()), m.group()));
      }
    }
    return matches;
  }

  /** snakeyaml-engine columns count code points; Java strings index UTF-16 chars. */
  private int toCharColumn(int zeroBasedLine, int codePointColumn) {
    String text = zeroBasedLine < lines.size() ? lines.get(zeroBasedLine) : "";
    int cps = text.codePointCount(0, text.length());
    return text.offsetByCodePoints(0, Math.min(codePointColumn, cps)) + Math.max(0, codePointColumn - cps);
  }
}
