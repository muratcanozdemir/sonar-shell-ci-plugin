package io.github.shellci.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sonarsource.scanner.engine.sensor.test.fixtures.TestInputFileBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;

class LocationsTest {

  private static final String CONTENT = "first line\n\nthird\n";
  private static final List<String> LINES = FileContents.lines(CONTENT);
  private static final InputFile FILE = TestInputFileBuilder.create("module", "a.sh").initMetadata(CONTENT).build();

  private static String text(TextRange range) {
    return range.start().line() + ":" + range.start().lineOffset() + "-" + range.end().line() + ":" + range.end().lineOffset();
  }

  @Test
  void exact_range() {
    assertThat(text(Locations.range(FILE, LINES, 1, 0, 1, 5))).isEqualTo("1:0-1:5");
    assertThat(text(Locations.range(FILE, LINES, 1, 6, 3, 2))).isEqualTo("1:6-3:2");
  }

  @Test
  void out_of_bounds_positions_are_clamped() {
    assertThat(text(Locations.range(FILE, LINES, 0, -4, 1, 99))).isEqualTo("1:0-1:10");
    // the end clamps to the last (empty) line of the file
    assertThat(text(Locations.range(FILE, LINES, 3, 1, 99, 99))).isEqualTo("3:1-4:0");
  }

  @Test
  void empty_ranges_are_widened() {
    // zero-width inside a line: one character
    assertThat(text(Locations.range(FILE, LINES, 1, 3, 1, 3))).isEqualTo("1:3-1:4");
    // zero-width at the end of a line: the whole line
    assertThat(text(Locations.range(FILE, LINES, 3, 5, 3, 5))).isEqualTo("3:0-3:5");
    // on an empty line no range exists: the caller reports on the file
    assertThat(Locations.range(FILE, LINES, 2, 0, 2, 0)).isNull();
  }

  @Test
  void line_list_shorter_than_file() {
    assertThat(Locations.range(FILE, List.of(), 1, 0, 1, 3)).isNull();
  }

  @Test
  void line_splitting() {
    assertThat(FileContents.lines("a\r\nb\rc\n")).containsExactly("a", "b", "c", "");
  }
}
