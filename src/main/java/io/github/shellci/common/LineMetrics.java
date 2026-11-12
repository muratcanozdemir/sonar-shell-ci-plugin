package io.github.shellci.common;

import java.util.List;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;

/**
 * Saves lines-of-code and comment-line measures for languages using {@code #} line comments (shell, YAML). A line is
 * code when it has any non-blank content that is not a comment; a {@code #!} shebang counts as a comment.
 */
public final class LineMetrics {

  private LineMetrics() {
  }

  public static void save(SensorContext context, FileLinesContextFactory factory, InputFile file, List<String> lines) {
    FileLinesContext fileLines = factory.createFor(file);
    int ncloc = 0;
    int comments = 0;
    for (int i = 0; i < lines.size(); i++) {
      String trimmed = lines.get(i).strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.startsWith("#")) {
        comments++;
      } else {
        ncloc++;
        fileLines.setIntValue(CoreMetrics.NCLOC_DATA_KEY, i + 1, 1);
      }
    }
    fileLines.save();
    context.<Integer>newMeasure().on(file).forMetric(CoreMetrics.NCLOC).withValue(ncloc).save();
    context.<Integer>newMeasure().on(file).forMetric(CoreMetrics.COMMENT_LINES).withValue(comments).save();
  }
}
