package io.github.shellci.ci.checks;

import static io.github.shellci.ci.checks.CheckVerifier.lines;
import static io.github.shellci.ci.checks.CheckVerifier.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChecksTest {

  @Test
  void script_injection() {
    List<CheckVerifier.Issue> issues = run(new ScriptInjectionCheck(), "samples/github-actions/.github/workflows/injection.yml");
    assertThat(lines(issues)).containsExactly(11, 14, 21, 22);
    assertThat(issues.get(0).text()).isEqualTo("${{ github.event.issue.title }}");
    assertThat(issues.get(0).message()).contains("github.event.issue.title").contains("environment variable");
    assertThat(issues.get(1).text()).isEqualTo("${{ github.event.pull_request.body }}");
    assertThat(issues.get(2).message()).contains("JavaScript code");
    assertThat(issues.get(3).text()).isEqualTo("${{ toJSON(github.event) }}");
  }

  @Test
  void script_injection_in_composite_action() {
    assertThat(lines(run(new ScriptInjectionCheck(), "github/action.yml"))).containsExactly(7);
  }

  @Test
  void script_injection_ignores_trusted_contexts() {
    assertThat(run(new ScriptInjectionCheck(), "github/safe.yml")).isEmpty();
  }

  @Test
  void untrusted_checkout() {
    List<CheckVerifier.Issue> issues = run(new UntrustedCheckoutCheck(), "samples/github-actions/.github/workflows/pwn.yml");
    assertThat(lines(issues)).containsExactly(11, 14);
    assertThat(issues.get(0).text()).isEqualTo("${{ github.event.pull_request.head.sha }}");
    assertThat(issues.get(0).secondaries()).hasSize(1);
    assertThat(issues.get(0).secondaries().get(0).span().line()).isEqualTo(2);
    assertThat(issues.get(1).text()).isEqualTo("gh pr checkout");
  }

  @Test
  void untrusted_checkout_only_in_privileged_workflows() {
    assertThat(run(new UntrustedCheckoutCheck(), "github/safe.yml")).isEmpty();
  }

  @Test
  void unpinned_actions() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedReferenceCheck(), "samples/github-actions/.github/workflows/misc.yml");
    assertThat(lines(issues)).containsExactly(18, 10, 11, 13);
    assertThat(issues.get(0).text()).isEqualTo("other-org/workflows/.github/workflows/release.yml@v1");
    assertThat(issues.get(1).message()).contains("\"main\"");
    assertThat(issues.get(2).message()).contains("full-length commit SHA");
  }

  @Test
  void docker_steps_are_left_to_unpinned_image() {
    assertThat(run(new UnpinnedReferenceCheck(), "samples/github-actions/.github/workflows/supply-chain.yml")).isEmpty();
  }

  @Test
  void github_supply_chain_sample() {
    String fixture = "samples/github-actions/.github/workflows/supply-chain.yml";
    List<CheckVerifier.Issue> images = run(new UnpinnedImageCheck(), fixture);
    assertThat(lines(images)).containsExactly(9, 12, 16);
    assertThat(images.get(2).text()).isEqualTo("docker://alpine");
    assertThat(images.get(2).message()).contains("implicit \"latest\"");
    assertThat(lines(run(new UnpinnedImageCheck(), fixture, Map.of(UnpinnedImageCheck.REQUIRE_DIGEST, "true"))))
      .containsExactly(9, 12, 14, 16, 17);
    assertThat(lines(run(new HardcodedSecretCheck(), fixture))).containsExactly(5);
    assertThat(lines(run(new RemoteScriptExecutionCheck(), fixture))).containsExactly(18);
    assertThat(run(new MissingPermissionsCheck(), fixture)).isEmpty();
  }

  @Test
  void unpinned_actions_trusted_owners() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedReferenceCheck(), "samples/github-actions/.github/workflows/misc.yml",
      Map.of(UnpinnedReferenceCheck.TRUSTED_OWNERS, " Actions , other-org"));
    assertThat(lines(issues)).containsExactly(10, 11);
  }

  @Test
  void pinned_and_local_actions_are_fine() {
    assertThat(run(new UnpinnedReferenceCheck(), "github/safe.yml")).isEmpty();
  }

  @Test
  void write_all_permissions() {
    List<CheckVerifier.Issue> issues = run(new WriteAllPermissionsCheck(), "samples/github-actions/.github/workflows/pwn.yml");
    assertThat(lines(issues)).containsExactly(4);
    assertThat(issues.get(0).text()).isEqualTo("write-all");
  }

  @Test
  void missing_permissions() {
    List<CheckVerifier.Issue> issues = run(new MissingPermissionsCheck(), "samples/github-actions/.github/workflows/misc.yml");
    assertThat(lines(issues)).containsExactly(5, 17);
    assertThat(issues.get(0).text()).isEqualTo("build");
    assertThat(run(new MissingPermissionsCheck(), "github/safe.yml")).isEmpty();
    assertThat(run(new MissingPermissionsCheck(), "github/action.yml")).isEmpty();
    // "permissions: {}" (no permissions at all) counts as declared
    assertThat(run(new MissingPermissionsCheck(), "samples/github-actions/.github/workflows/scripts.yml")).isEmpty();
  }

  @Test
  void secrets_inherit() {
    assertThat(lines(run(new SecretsInheritCheck(), "samples/github-actions/.github/workflows/misc.yml"))).containsExactly(19);
    // local reusable workflows may inherit
    assertThat(run(new SecretsInheritCheck(), "github/safe.yml")).isEmpty();
  }

  @Test
  void secrets_to_json() {
    List<CheckVerifier.Issue> issues = run(new SecretsToJsonCheck(), "samples/github-actions/.github/workflows/misc.yml");
    assertThat(lines(issues)).containsExactly(16);
    assertThat(issues.get(0).text()).isEqualTo("toJSON(secrets)");
  }

  @Test
  void unsecure_commands() {
    List<CheckVerifier.Issue> issues = run(new UnsecureCommandsCheck(), "samples/github-actions/.github/workflows/misc.yml");
    assertThat(lines(issues)).containsExactly(3);
  }

  @Test
  void checkout_persist_credentials() {
    assertThat(lines(run(new CheckoutPersistCredentialsCheck(), "samples/github-actions/.github/workflows/pwn.yml"))).containsExactly(13);
    assertThat(run(new CheckoutPersistCredentialsCheck(), "github/safe.yml")).isEmpty();
  }
}
