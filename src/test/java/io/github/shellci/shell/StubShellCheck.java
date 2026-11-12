package io.github.shellci.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Writes fake "shellcheck" executables (POSIX sh scripts) to exercise the runner's error handling. */
public final class StubShellCheck {

  private StubShellCheck() {
  }

  /**
   * @param versionOutput what {@code --version} prints (exit 0), or null to make {@code --version} fail
   * @param analysis      shell commands run for any other invocation
   */
  public static Path create(Path dir, String versionOutput, String analysis) throws IOException {
    String version = versionOutput == null ? "exit 1" : "printf '%s\\n' '" + versionOutput.replace("'", "'\\''") + "'; exit 0";
    Path stub = dir.resolve("shellcheck-stub-" + System.nanoTime());
    Files.writeString(stub, "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then " + version + "; fi\n" + analysis + "\n");
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
    return stub;
  }

  public static boolean posix() {
    return !System.getProperty("os.name").toLowerCase().startsWith("windows");
  }
}
