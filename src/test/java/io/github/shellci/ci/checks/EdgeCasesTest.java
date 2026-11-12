package io.github.shellci.ci.checks;

import static io.github.shellci.ci.checks.CheckVerifier.lines;
import static io.github.shellci.ci.checks.CheckVerifier.run;
import static io.github.shellci.ci.checks.CheckVerifier.runText;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.shellci.ci.Platform;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unusual but valid inputs: non-scalar values, dynamic references, empty documents, and values that must not be reported. */
class EdgeCasesTest {

  private static final List<CiCheck> ALL = List.of(new ScriptInjectionCheck(), new UntrustedCheckoutCheck(),
    new HardcodedSecretCheck(), new SecretsToJsonCheck(), new UnsecureCommandsCheck(), new UnpinnedReferenceCheck(),
    new UnpinnedImageCheck(), new RemoteScriptExecutionCheck(), new WriteAllPermissionsCheck(),
    new MissingPermissionsCheck(), new SecretsInheritCheck(), new CheckoutPersistCredentialsCheck());

  @Test
  void empty_documents_are_ignored_by_every_check() {
    for (Platform platform : Platform.values()) {
      for (CiCheck check : ALL) {
        assertThat(runText(check, "", platform)).as(check.getClass().getSimpleName()).isEmpty();
        assertThat(runText(check, "# only a comment\n", platform)).as(check.getClass().getSimpleName()).isEmpty();
      }
    }
  }

  @Test
  void github_containers_and_services() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedImageCheck(), "github/edge.yml");
    assertThat(lines(issues)).containsExactly(7, 9);
    assertThat(issues.get(0).text()).isEqualTo("node");
    assertThat(lines(run(new UnpinnedImageCheck(), "github/edge.yml", Map.of(UnpinnedImageCheck.REQUIRE_DIGEST, "true"))))
      .containsExactly(7, 9, 11);
  }

  @Test
  void image_tags() {
    assertThat(UnpinnedImageCheck.tag("registry:5000/team/app")).isNull();
    assertThat(UnpinnedImageCheck.tag("registry:5000/team/app:1.2")).isEqualTo("1.2");
    assertThat(UnpinnedImageCheck.tag("app:LATEST@sha256:abc")).isEqualTo("latest");
    assertThat(UnpinnedImageCheck.tag("app")).isNull();
  }

  @Test
  void dynamic_and_non_scalar_references_are_skipped() {
    // ${{ matrix.action }} and a sequence as "uses"; pinned reusable workflow
    assertThat(run(new UnpinnedReferenceCheck(), "github/edge.yml")).isEmpty();
    assertThat(run(new ScriptInjectionCheck(), "github/edge.yml")).isEmpty();
  }

  @Test
  void job_level_permissions_and_secrets() {
    assertThat(lines(run(new WriteAllPermissionsCheck(), "github/edge.yml"))).containsExactly(24);
    // job-perms has no "uses": "secrets: inherit" is meaningless there; reusable passes secrets explicitly
    assertThat(run(new SecretsInheritCheck(), "github/edge.yml")).isEmpty();
    assertThat(lines(run(new MissingPermissionsCheck(), "github/edge.yml"))).containsExactly(28);
    assertThat(lines(run(new CheckoutPersistCredentialsCheck(), "github/edge.yml"))).containsExactly(18);
    assertThat(run(new UntrustedCheckoutCheck(), "github/edge.yml")).isEmpty();
  }

  @Test
  void workflow_without_on_key() {
    assertThat(lines(run(new ScriptInjectionCheck(), "github/no-on.yml"))).containsExactly(4);
    assertThat(run(new UntrustedCheckoutCheck(), "github/no-on.yml")).isEmpty();
  }

  @Test
  void untrusted_checkout_ignores_non_scalar_and_safe_refs() {
    String yaml = String.join("\n",
      "on: pull_request_target",
      "jobs:",
      "  a:",
      "    steps:",
      "      - uses: actions/checkout@v4",
      "        with:",
      "          ref: [not, scalar]",
      "      - uses: actions/checkout@v4",
      "        with:",
      "          ref: main",
      "      - uses: other/checkout@v1",
      "        with:",
      "          ref: ${{ github.event.pull_request.head.sha }}",
      "      - run: [not, scalar]",
      "");
    assertThat(runText(new UntrustedCheckoutCheck(), yaml, Platform.GITHUB_ACTIONS)).isEmpty();
  }

  @Test
  void azure_edge_cases() {
    String fixture = "azure/edge.azure-pipelines.yml";
    List<CheckVerifier.Issue> injection = run(new ScriptInjectionCheck(), fixture);
    assertThat(lines(injection)).containsExactly(11);
    assertThat(injection.get(0).message()).contains("BUILD_REQUESTEDFOR");
    assertThat(lines(run(new UnpinnedImageCheck(), fixture))).containsExactly(28);
    assertThat(run(new CheckoutPersistCredentialsCheck(), fixture)).isEmpty();
  }

  @Test
  void gitlab_edge_cases() {
    assertThat(lines(run(new UnpinnedReferenceCheck(), "gitlab/edge.yml"))).containsExactly(2);
    assertThat(lines(run(new UnpinnedImageCheck(), "gitlab/edge.yml"))).containsExactly(4);
    // "true", digits only, *_PATH names, absolute paths, short values and value-less entries are not secrets
    assertThat(run(new HardcodedSecretCheck(), "gitlab/edge.yml")).isEmpty();
  }

  @Test
  void gitlab_components() {
    List<CheckVerifier.Issue> issues = run(new UnpinnedReferenceCheck(), "gitlab/components.yml");
    assertThat(lines(issues)).containsExactly(2);
    assertThat(issues.get(0).message()).contains("\"default\"");
  }

  @Test
  void remote_execution_variants() {
    String yaml = String.join("\n",
      "job:",
      "  script:",
      "    - wget -qO- https://x.example/i.sh | sudo -E bash -s -- --yes",
      "    - sh -c \"$(curl -fsSL https://x.example/i.sh)\"",
      "    - curl -o i.sh https://x.example/i.sh && sha256sum -c i.sh.sha256 && sh i.sh",
      "    - \"curl -s https://x.example/i.sh | bash\"",
      "");
    List<CheckVerifier.Issue> issues = runText(new RemoteScriptExecutionCheck(), yaml, Platform.GITLAB_CI);
    assertThat(lines(issues)).containsExactly(3, 4, 6);

    String azure = String.join("\n",
      "steps:",
      "  - pwsh: iwr https://x.example/install.ps1 | iex",
      "  - powershell: iex ((New-Object Net.WebClient).DownloadString('https://x.example/i.ps1'))",
      "  - powershell: Invoke-Expression (Invoke-WebRequest https://x.example/i.ps1)",
      "  - powershell: Invoke-WebRequest https://x.example/i.zip -OutFile i.zip",
      "  - powershell: (New-Object System.Net.WebClient).DownloadString('https://x.example/i.ps1') | iex",
      "  - powershell: iex ((New-Object System.Net.WebClient).DownloadString('https://x.example/i.ps1'))",
      "");
    assertThat(lines(runText(new RemoteScriptExecutionCheck(), azure, Platform.AZURE_PIPELINES))).containsExactly(2, 3, 4, 6, 7);
  }

  @Test
  void remote_execution_with_unmappable_columns_reports_the_line() {
    // escapes in a double-quoted scalar shift columns: the whole line is reported
    String yaml = "job:\n  script:\n    - \"echo \\\"x\\\"; curl -s https://x.example/i.sh | bash\"\n";
    List<CheckVerifier.Issue> issues = runText(new RemoteScriptExecutionCheck(), yaml, Platform.GITLAB_CI);
    assertThat(lines(issues)).containsExactly(3);
    assertThat(issues.get(0).span().column()).isZero();
  }

  @Test
  void hardcoded_secrets_in_github_env() {
    String yaml = String.join("\n",
      "on: push",
      "env:",
      "  NPM_TOKEN: npm_a8D7f6G5h4J3k2L1",
      "  GH_TOKEN: ${{ secrets.GH_TOKEN }}",
      "  TOKEN_HEADER: Authorization",
      "jobs:",
      "  a:",
      "    steps:",
      "      - env:",
      "          dbPassword: hunter2hunter2",
      "        run: echo",
      "");
    assertThat(lines(runText(new HardcodedSecretCheck(), yaml, Platform.GITHUB_ACTIONS))).containsExactly(3, 10);
  }

  @Test
  void unsecure_commands_needs_true() {
    String yaml = "on: push\nenv:\n  ACTIONS_ALLOW_UNSECURE_COMMANDS: [true]\njobs: {}\n";
    assertThat(runText(new UnsecureCommandsCheck(), yaml, Platform.GITHUB_ACTIONS)).isEmpty();
    assertThat(runText(new UnsecureCommandsCheck(), "on: push\nenv:\n  ACTIONS_ALLOW_UNSECURE_COMMANDS: 'TRUE'\n", Platform.GITHUB_ACTIONS))
      .hasSize(1);
  }

  @Test
  void write_all_needs_the_exact_value() {
    assertThat(runText(new WriteAllPermissionsCheck(), "on: push\npermissions: read-all\njobs: {}\n", Platform.GITHUB_ACTIONS)).isEmpty();
    assertThat(runText(new WriteAllPermissionsCheck(), "on: push\npermissions:\n  contents: write\njobs: {}\n", Platform.GITHUB_ACTIONS)).isEmpty();
  }

  @Test
  void secrets_inherit_needs_inherit() {
    String yaml = "on: push\njobs:\n  a:\n    uses: o/r/.github/workflows/w.yml@v1\n    secrets: none\n";
    assertThat(runText(new SecretsInheritCheck(), yaml, Platform.GITHUB_ACTIONS)).isEmpty();
  }

  @Test
  void checkout_credentials_with_non_scalar_values() {
    String github = "on: push\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n        with:\n          persist-credentials: [false]\n";
    assertThat(runText(new CheckoutPersistCredentialsCheck(), github, Platform.GITHUB_ACTIONS)).hasSize(1);
    String azure = "steps:\n  - checkout: self\n    persistCredentials: [true]\n";
    assertThat(runText(new CheckoutPersistCredentialsCheck(), azure, Platform.AZURE_PIPELINES)).isEmpty();
  }
}
