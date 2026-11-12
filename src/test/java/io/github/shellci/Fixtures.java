package io.github.shellci;

import io.github.shellci.ci.Platform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pipeline files used by the tests. A fixture is either a sample project file ("samples/gitlab-ci/.gitlab-ci.yml"),
 * so the samples stay covered by the tests and need no copy, or a test-only file under src/test/resources/ci
 * ("github/edge.yml").
 */
public final class Fixtures {

  private Fixtures() {
  }

  public static Path path(String fixture) {
    return fixture.startsWith("samples/") ? Path.of(fixture) : Path.of("src/test/resources/ci", fixture);
  }

  public static String read(String fixture) {
    try {
      return Files.readString(path(fixture), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Test fixtures live in ci/github, ci/gitlab and ci/azure; samples in samples/github-actions, gitlab-ci, azure-pipelines. */
  public static Platform platformOf(String fixture) {
    if (fixture.startsWith("gitlab/") || fixture.startsWith("samples/gitlab-ci/")) {
      return Platform.GITLAB_CI;
    }
    if (fixture.startsWith("azure/") || fixture.startsWith("samples/azure-pipelines/")) {
      return Platform.AZURE_PIPELINES;
    }
    return Platform.GITHUB_ACTIONS;
  }
}
