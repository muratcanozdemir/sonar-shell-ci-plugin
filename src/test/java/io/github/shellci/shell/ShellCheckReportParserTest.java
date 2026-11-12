package io.github.shellci.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellCheckReportParserTest {

  private static List<ShellCheckComment> parse(String json) {
    return ShellCheckReportParser.parse(new StringReader(json));
  }

  @Test
  void json1_format() {
    List<ShellCheckComment> comments = parse("{\"comments\":[{\"file\":\"a.sh\",\"line\":3,\"endLine\":4,\"column\":2,"
      + "\"endColumn\":9,\"level\":\"error\",\"code\":1035,\"message\":\"You need a space here\"}]}");
    assertThat(comments).containsExactly(new ShellCheckComment("a.sh", 3, 4, 2, 9, "error", 1035, "You need a space here"));
  }

  @Test
  void legacy_json_format_with_missing_fields() {
    List<ShellCheckComment> comments = parse("[{\"line\":5,\"code\":2086,\"endLine\":null}]");
    // defaults: no file, end = start, level warning, empty message
    assertThat(comments).containsExactly(new ShellCheckComment("", 5, 5, 1, 1, "warning", 2086, ""));
  }

  @Test
  void empty_reports() {
    assertThat(parse("")).isEmpty();
    assertThat(parse("null")).isEmpty();
    assertThat(parse("[]")).isEmpty();
  }

  @Test
  void invalid_reports() {
    assertThatThrownBy(() -> parse("{not json")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid");
    assertThatThrownBy(() -> parse("{\"issues\":[]}")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("json1");
    assertThatThrownBy(() -> parse("42")).isInstanceOf(IllegalArgumentException.class);
  }
}
