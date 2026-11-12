package io.github.shellci.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes the ShellCheck binary and parses its json1 output. */
public class ShellCheckRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ShellCheckRunner.class);
  /** Keeps command lines well below OS argument-length limits. */
  static final int BATCH_SIZE = 100;

  private final String executable;
  private final long timeoutSeconds;

  public ShellCheckRunner(String executable, long timeoutSeconds) {
    this.executable = executable;
    this.timeoutSeconds = timeoutSeconds;
  }

  /** Returns the ShellCheck version, or empty if the executable cannot be run. */
  public Optional<String> version() {
    try {
      Result result = exec(List.of(executable, "--version"));
      if (result.exitCode != 0) {
        return Optional.empty();
      }
      return result.stdout.lines()
        .filter(l -> l.startsWith("version:"))
        .map(l -> l.substring("version:".length()).trim())
        .findFirst()
        .or(() -> Optional.of("unknown"));
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * Analyzes the given files. {@code dialect} forces a shell dialect (sh, bash, dash, ksh, busybox) or may be null to
   * let ShellCheck infer it from the shebang/extension.
   */
  public List<ShellCheckComment> analyze(List<Path> files, String dialect) throws IOException, InterruptedException {
    List<ShellCheckComment> comments = new ArrayList<>();
    for (int i = 0; i < files.size(); i += BATCH_SIZE) {
      List<String> command = new ArrayList<>(List.of(executable, "--format=json1", "--severity=style"));
      if (dialect != null) {
        command.add("--shell=" + dialect);
      }
      files.subList(i, Math.min(files.size(), i + BATCH_SIZE)).forEach(f -> command.add(f.toString()));
      Result result = exec(command);
      // 0: no issues, 1: issues found, 2: some files could not be processed. Anything else is a usage error.
      if (result.exitCode > 2) {
        throw new IOException("ShellCheck failed with exit code " + result.exitCode + ": " + result.stderr.strip());
      }
      if (!result.stderr.isBlank()) {
        LOG.warn("ShellCheck reported: {}", result.stderr.strip());
      }
      if (!result.stdout.isBlank()) {
        comments.addAll(ShellCheckReportParser.parse(new StringReader(result.stdout)));
      }
    }
    return comments;
  }

  private Result exec(List<String> command) throws IOException, InterruptedException {
    LOG.debug("Running {}", command.size() > 6 ? command.subList(0, 6) + " ... (" + command.size() + " args)" : command);
    Process process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(nullFile())).start();
    CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
    CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("ShellCheck did not finish within " + timeoutSeconds + " seconds");
    }
    try {
      return new Result(process.exitValue(), stdout.get(), stderr.get());
    } catch (ExecutionException e) {
      throw new IOException("Unable to read ShellCheck output", e.getCause());
    }
  }

  private static java.io.File nullFile() {
    return new java.io.File(System.getProperty("os.name").toLowerCase().startsWith("windows") ? "NUL" : "/dev/null");
  }

  private static String read(InputStream stream) {
    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      StringBuilder sb = new StringBuilder();
      char[] buffer = new char[8192];
      int n;
      while ((n = reader.read(buffer)) != -1) {
        sb.append(buffer, 0, n);
      }
      return sb.toString();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private record Result(int exitCode, String stdout, String stderr) {
  }
}
