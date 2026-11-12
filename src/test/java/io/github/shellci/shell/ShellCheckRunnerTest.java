package io.github.shellci.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellCheckRunnerTest {

  @TempDir
  Path dir;

  private static final String ONE_COMMENT =
    "{\"comments\":[{\"file\":\"a.sh\",\"line\":2,\"endLine\":2,\"column\":6,\"endColumn\":8,\"level\":\"info\",\"code\":2086,\"message\":\"m\"}]}";

  @BeforeEach
  void posixOnly() {
    assumeTrue(StubShellCheck.posix(), "stub executables are POSIX shell scripts");
  }

  @Test
  void version() throws IOException {
    assertThat(new ShellCheckRunner(StubShellCheck.create(dir, "ShellCheck\nversion: 1.2.3", "").toString(), 10).version())
      .contains("1.2.3");
    assertThat(new ShellCheckRunner(StubShellCheck.create(dir, "no version line", "").toString(), 10).version())
      .contains("unknown");
    assertThat(new ShellCheckRunner(StubShellCheck.create(dir, null, "").toString(), 10).version()).isEmpty();
    assertThat(new ShellCheckRunner(dir.resolve("missing").toString(), 10).version()).isEmpty();
  }

  @Test
  void parses_output_and_tolerates_stderr() throws Exception {
    Path stub = StubShellCheck.create(dir, "version: 1", "echo 'a.sh: warning' >&2; echo '" + ONE_COMMENT + "'; exit 1");
    List<ShellCheckComment> comments = new ShellCheckRunner(stub.toString(), 10).analyze(List.of(dir.resolve("a.sh")), "bash");
    assertThat(comments).extracting(ShellCheckComment::ruleKey).containsExactly("SC2086");
  }

  @Test
  void empty_output_means_no_comments() throws Exception {
    Path stub = StubShellCheck.create(dir, "version: 1", "exit 0");
    assertThat(new ShellCheckRunner(stub.toString(), 10).analyze(List.of(dir.resolve("a.sh")), null)).isEmpty();
  }

  @Test
  void usage_errors_fail() throws IOException {
    Path stub = StubShellCheck.create(dir, "version: 1", "echo 'bad option' >&2; exit 3");
    ShellCheckRunner runner = new ShellCheckRunner(stub.toString(), 10);
    assertThatThrownBy(() -> runner.analyze(List.of(dir.resolve("a.sh")), null))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("exit code 3")
      .hasMessageContaining("bad option");
  }

  @Test
  void timeout() throws IOException {
    Path stub = StubShellCheck.create(dir, "version: 1", "sleep 10");
    ShellCheckRunner runner = new ShellCheckRunner(stub.toString(), 1);
    assertThatThrownBy(() -> runner.analyze(List.of(dir.resolve("a.sh")), null))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("did not finish within 1 seconds");
  }

  @Test
  void large_file_lists_are_batched() throws Exception {
    Path calls = dir.resolve("calls");
    Path stub = StubShellCheck.create(dir, "version: 1", "echo \"$#\" >> '" + calls + "'; echo '{\"comments\":[]}'");
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < ShellCheckRunner.BATCH_SIZE + 5; i++) {
      files.add(dir.resolve("f" + i + ".sh"));
    }
    new ShellCheckRunner(stub.toString(), 10).analyze(files, "sh");
    // --format, --severity, --shell, then the files of the batch
    assertThat(Files.readAllLines(calls)).containsExactly(String.valueOf(3 + ShellCheckRunner.BATCH_SIZE), "8");
  }
}
