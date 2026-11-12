package io.github.shellci.common;

import java.util.List;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;

/** Turns tool-reported positions into valid {@link TextRange}s, degrading to whole-line ranges when positions are off. */
public final class Locations {

  private Locations() {
  }

  /**
   * Returns a range, or {@code null} when the position points at an empty line (report on the file then).
   *
   * @param lines     file content split into lines (no terminators)
   * @param line      1-based start line
   * @param column    0-based start column (in chars)
   * @param endLine   1-based end line
   * @param endColumn 0-based exclusive end column (in chars)
   */
  public static TextRange range(InputFile file, List<String> lines, int line, int column, int endLine, int endColumn) {
    int lineCount = Math.max(1, Math.min(file.lines(), lines.size()));
    int startLine = clamp(line, 1, lineCount);
    int stopLine = clamp(endLine, startLine, lineCount);
    int startLength = lineLength(lines, startLine);
    int stopLength = lineLength(lines, stopLine);
    int startCol = clamp(column, 0, startLength);
    int stopCol = clamp(endColumn, 0, stopLength);
    if (stopLine == startLine && stopCol <= startCol) {
      if (startCol < startLength) {
        stopCol = startCol + 1;
      } else if (startLength > 0) {
        return file.selectLine(startLine);
      } else {
        // An empty line cannot hold a non-empty range: the caller reports the issue on the file instead.
        return null;
      }
    }
    return file.newRange(startLine, startCol, stopLine, stopCol);
  }

  private static int lineLength(List<String> lines, int line) {
    return line - 1 < lines.size() ? lines.get(line - 1).length() : 0;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
