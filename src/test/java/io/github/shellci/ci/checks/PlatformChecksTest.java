package io.github.shellci.ci.checks;

import static io.github.shellci.ci.checks.CheckVerifier.lines;
import static io.github.shellci.ci.checks.CheckVerifier.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** GitLab CI and Azure Pipelines behaviour, plus the rules shared by all platforms. */
class PlatformChecksTest {

  private static final String GITLAB = "samples/gitlab-ci/.gitlab-ci.yml";
  private static final String AZURE = "samples/azure-pipelines/azure-pipelines.yml";

  @Test
  void gitlab_unpinned_includes() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedReferenceCheck(), GITLAB);
    assertThat(lines(issues)).containsExactly(3, 4, 7, 12, 15);
    assertThat(issues.get(0).message()).contains("URL");
    assertThat(issues.get(1).message()).contains("default branch");
    assertThat(issues.get(2).text()).isEqualTo("main");
    assertThat(issues.get(3).message()).contains("\"1.2.0\"");
  }

  @Test
  void gitlab_trusted_groups() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedReferenceCheck(), GITLAB,
      Map.of(UnpinnedReferenceCheck.TRUSTED_OWNERS, "other-group,acme"));
    assertThat(lines(issues)).containsExactly(3, 15);
  }

  @Test
  void gitlab_images() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedImageCheck(), GITLAB);
    assertThat(lines(issues)).containsExactly(26, 36);
    assertThat(issues.get(0).message()).contains("implicit \"latest\"");
    assertThat(lines(run(new UnpinnedImageCheck(), GITLAB, Map.of(UnpinnedImageCheck.REQUIRE_DIGEST, "true"))))
      .containsExactly(26, 34, 36, 51);
  }

  @Test
  void gitlab_component_file_uses_the_last_document() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedImageCheck(), "samples/gitlab-ci/.gitlab/ci/scanner-component.yml");
    // registry host with a port, no tag
    assertThat(lines(issues)).containsExactly(8);
  }

  @Test
  void gitlab_hardcoded_secrets() {
    List<CheckVerifier.Issue> issues = run(new HardcodedSecretCheck(), GITLAB);
    assertThat(lines(issues)).containsExactly(18, 21);
    assertThat(issues.get(0).message()).contains("DEPLOY_TOKEN");
  }

  @Test
  void remote_script_execution() {
    List<CheckVerifier.Issue> gitlab = run(new RemoteScriptExecutionCheck(), GITLAB);
    assertThat(lines(gitlab)).containsExactly(48, 58);
    assertThat(gitlab.get(0).text()).isEqualTo("curl -fsSL https://get.example.com/install.sh | sh");
    assertThat(lines(run(new RemoteScriptExecutionCheck(), AZURE))).containsExactly(39);
    assertThat(run(new RemoteScriptExecutionCheck(), "github/safe.yml")).isEmpty();
  }

  @Test
  void azure_script_injection() {
    List<CheckVerifier.Issue> issues = run(new ScriptInjectionCheck(), AZURE);
    assertThat(lines(issues)).containsExactly(31, 34, 40);
    assertThat(issues.get(0).text()).isEqualTo("$(Build.SourceVersionMessage)");
    assertThat(issues.get(0).message()).contains("BUILD_SOURCEVERSIONMESSAGE");
    assertThat(issues.get(1).text()).isEqualTo("${{ variables['Build.SourceBranch'] }}");
    // $(Build.SourcesDirectory) is a trusted path, not reported
  }

  @Test
  void azure_secrets_images_and_credentials() {
    assertThat(lines(run(new HardcodedSecretCheck(), AZURE))).containsExactly(9);
    assertThat(lines(run(new UnpinnedImageCheck(), AZURE))).containsExactly(17);
    assertThat(lines(run(new CheckoutPersistCredentialsCheck(), AZURE))).containsExactly(30);
  }

  @Test
  void github_rules_ignore_other_platforms() {
    for (CiCheck check : List.of(new UntrustedCheckoutCheck(), new WriteAllPermissionsCheck(), new MissingPermissionsCheck(),
      new SecretsInheritCheck(), new SecretsToJsonCheck(), new UnsecureCommandsCheck())) {
      assertThat(run(check, GITLAB)).as(check.getClass().getSimpleName()).isEmpty();
      assertThat(run(check, AZURE)).as(check.getClass().getSimpleName()).isEmpty();
    }
  }

  @Test
  void github_hardcoded_secrets_and_images() {
    assertThat(run(new HardcodedSecretCheck(), "samples/github-actions/.github/workflows/misc.yml")).isEmpty();
    assertThat(run(new UnpinnedImageCheck(), "samples/github-actions/.github/workflows/misc.yml")).isEmpty();
  }
}
