package io.github.shellci.ci;

import static io.github.shellci.ci.Platform.AZURE_PIPELINES;
import static io.github.shellci.ci.Platform.GITHUB_ACTIONS;
import static io.github.shellci.ci.Platform.GITLAB_CI;

import io.github.shellci.ci.checks.CheckoutPersistCredentialsCheck;
import io.github.shellci.ci.checks.CiCheck;
import io.github.shellci.ci.checks.HardcodedSecretCheck;
import io.github.shellci.ci.checks.MissingPermissionsCheck;
import io.github.shellci.ci.checks.RemoteScriptExecutionCheck;
import io.github.shellci.ci.checks.ScriptInjectionCheck;
import io.github.shellci.ci.checks.SecretsInheritCheck;
import io.github.shellci.ci.checks.SecretsToJsonCheck;
import io.github.shellci.ci.checks.UnpinnedImageCheck;
import io.github.shellci.ci.checks.UnpinnedReferenceCheck;
import io.github.shellci.ci.checks.UnsecureCommandsCheck;
import io.github.shellci.ci.checks.UntrustedCheckoutCheck;
import io.github.shellci.ci.checks.WriteAllPermissionsCheck;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.sonar.api.issue.impact.Severity;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rules.CleanCodeAttribute;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.rule.RulesDefinition.OwaspTop10;

/** Metadata of every CI pipeline rule. Descriptions live in {@code ci/rules/<key>.html}. */
public enum CiRule {

  SCRIPT_INJECTION("ScriptInjection",
    "Untrusted data should not be expanded into pipeline scripts",
    RuleType.VULNERABILITY, Severity.BLOCKER, CleanCodeAttribute.COMPLETE, true,
    EnumSet.of(GITHUB_ACTIONS, AZURE_PIPELINES), new int[] {77, 78, 94}, new OwaspTop10[] {OwaspTop10.A3},
    Map.of(), ScriptInjectionCheck::new, "injection"),

  UNTRUSTED_CHECKOUT("UntrustedCheckout",
    "Privileged workflows should not check out untrusted pull request code",
    RuleType.VULNERABILITY, Severity.BLOCKER, CleanCodeAttribute.COMPLETE, true,
    EnumSet.of(GITHUB_ACTIONS), new int[] {829, 94}, new OwaspTop10[] {OwaspTop10.A8},
    Map.of(), UntrustedCheckoutCheck::new, "pwn-request"),

  HARDCODED_SECRET("HardcodedSecret",
    "Secrets should not be hardcoded in pipeline variables",
    RuleType.VULNERABILITY, Severity.BLOCKER, CleanCodeAttribute.TRUSTWORTHY, true,
    EnumSet.allOf(Platform.class), new int[] {798, 259}, new OwaspTop10[] {OwaspTop10.A7},
    Map.of(), HardcodedSecretCheck::new, "secrets"),

  SECRETS_TO_JSON("SecretsToJson",
    "Secrets should not be serialized all at once with toJSON(secrets)",
    RuleType.VULNERABILITY, Severity.HIGH, CleanCodeAttribute.COMPLETE, true,
    EnumSet.of(GITHUB_ACTIONS), new int[] {200, 359}, new OwaspTop10[] {OwaspTop10.A1},
    Map.of(), SecretsToJsonCheck::new, "secrets"),

  UNSECURE_COMMANDS("UnsecureCommands",
    "Insecure workflow commands should not be re-enabled",
    RuleType.VULNERABILITY, Severity.HIGH, CleanCodeAttribute.COMPLETE, true,
    EnumSet.of(GITHUB_ACTIONS), new int[] {77, 94}, new OwaspTop10[] {OwaspTop10.A5},
    Map.of(), UnsecureCommandsCheck::new),

  UNPINNED_REFERENCE("UnpinnedReference",
    "Using external actions, workflows, includes and components without a commit SHA is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.MEDIUM, CleanCodeAttribute.TRUSTWORTHY, true,
    EnumSet.of(GITHUB_ACTIONS, GITLAB_CI), new int[] {829, 494}, new OwaspTop10[] {OwaspTop10.A8},
    Map.of(UnpinnedReferenceCheck.TRUSTED_OWNERS, new Param("",
      "Comma-separated owners (GitHub users/organizations, GitLab groups) whose actions, workflows, projects and components "
        + "may be referenced by tag or branch, e.g. \"actions,my-org\".")),
    UnpinnedReferenceCheck::new, "supply-chain"),

  UNPINNED_IMAGE("UnpinnedImage",
    "Using container images without a pinned version is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.LOW, CleanCodeAttribute.TRUSTWORTHY, true,
    EnumSet.allOf(Platform.class), new int[] {829, 494}, new OwaspTop10[] {OwaspTop10.A8},
    Map.of(UnpinnedImageCheck.REQUIRE_DIGEST, new Param("false",
      "When true, every image not pinned to a digest (\"@sha256:...\") is reported, not only untagged and \"latest\" ones.")),
    UnpinnedImageCheck::new, "supply-chain", "docker"),

  REMOTE_SCRIPT_EXECUTION("RemoteScriptExecution",
    "Executing downloaded scripts without verification is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.MEDIUM, CleanCodeAttribute.TRUSTWORTHY, true,
    EnumSet.allOf(Platform.class), new int[] {494, 829}, new OwaspTop10[] {OwaspTop10.A8},
    Map.of(), RemoteScriptExecutionCheck::new, "supply-chain"),

  WRITE_ALL_PERMISSIONS("WriteAllPermissions",
    "Granting write access to all GITHUB_TOKEN scopes is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.MEDIUM, CleanCodeAttribute.TRUSTWORTHY, true,
    EnumSet.of(GITHUB_ACTIONS), new int[] {250, 732}, new OwaspTop10[] {OwaspTop10.A1},
    Map.of(), WriteAllPermissionsCheck::new, "permissions"),

  MISSING_PERMISSIONS("MissingPermissions",
    "Relying on default GITHUB_TOKEN permissions is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.LOW, CleanCodeAttribute.COMPLETE, true,
    EnumSet.of(GITHUB_ACTIONS), new int[] {250, 732}, new OwaspTop10[] {OwaspTop10.A1},
    Map.of(), MissingPermissionsCheck::new, "permissions"),

  SECRETS_INHERIT("SecretsInherit",
    "Passing all secrets to external reusable workflows is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.MEDIUM, CleanCodeAttribute.TRUSTWORTHY, true,
    EnumSet.of(GITHUB_ACTIONS), new int[] {200}, new OwaspTop10[] {OwaspTop10.A1},
    Map.of(), SecretsInheritCheck::new, "secrets"),

  CHECKOUT_PERSIST_CREDENTIALS("CheckoutPersistCredentials",
    "Persisting the job token after checkout is security-sensitive",
    RuleType.SECURITY_HOTSPOT, Severity.LOW, CleanCodeAttribute.TRUSTWORTHY, false,
    EnumSet.of(GITHUB_ACTIONS, AZURE_PIPELINES), new int[] {522}, new OwaspTop10[] {OwaspTop10.A4},
    Map.of(), CheckoutPersistCredentialsCheck::new, "secrets"),

  RUN_SHELLCHECK("RunShellCheck",
    "Shell scripts in pipeline steps should not have ShellCheck issues",
    RuleType.BUG, Severity.MEDIUM, CleanCodeAttribute.LOGICAL, true,
    EnumSet.allOf(Platform.class), new int[0], new OwaspTop10[0],
    orderedParams(
      RunShellCheckParams.MINIMUM_LEVEL, new Param("warning",
        "Lowest ShellCheck level reported: error, warning, info or style."),
      RunShellCheckParams.EXCLUDED_CODES, new Param("SC2154",
        "Comma-separated ShellCheck codes to ignore in pipeline scripts. SC2154 is excluded by default because variables "
          + "usually come from the pipeline environment.")),
    null, "shellcheck");

  public record Param(String defaultValue, String description) {
  }

  /** Parameter keys of {@link #RUN_SHELLCHECK}. */
  public static final class RunShellCheckParams {
    public static final String MINIMUM_LEVEL = "minimumLevel";
    public static final String EXCLUDED_CODES = "excludedCodes";

    private RunShellCheckParams() {
    }
  }

  private final String key;
  private final String title;
  private final RuleType type;
  private final Severity severity;
  private final CleanCodeAttribute attribute;
  private final boolean defaultActive;
  private final Set<Platform> platforms;
  private final int[] cwe;
  private final OwaspTop10[] owasp2021;
  private final Map<String, Param> params;
  private final Supplier<CiCheck> check;
  private final String[] tags;

  CiRule(String key, String title, RuleType type, Severity severity, CleanCodeAttribute attribute, boolean defaultActive,
    Set<Platform> platforms, int[] cwe, OwaspTop10[] owasp2021, Map<String, Param> params, Supplier<CiCheck> check,
    String... tags) {
    this.key = key;
    this.title = title;
    this.type = type;
    this.severity = severity;
    this.attribute = attribute;
    this.defaultActive = defaultActive;
    this.platforms = platforms;
    this.cwe = cwe;
    this.owasp2021 = owasp2021;
    this.params = params;
    this.check = check;
    this.tags = tags;
  }

  private static Map<String, Param> orderedParams(String k1, Param v1, String k2, Param v2) {
    Map<String, Param> params = new LinkedHashMap<>();
    params.put(k1, v1);
    params.put(k2, v2);
    return params;
  }

  public String key() {
    return key;
  }

  public String title() {
    return title;
  }

  public RuleType type() {
    return type;
  }

  public Severity severity() {
    return severity;
  }

  public CleanCodeAttribute attribute() {
    return attribute;
  }

  public boolean defaultActive() {
    return defaultActive;
  }

  public Set<Platform> platforms() {
    return platforms;
  }

  public int[] cwe() {
    return cwe.clone();
  }

  public OwaspTop10[] owasp2021() {
    return owasp2021.clone();
  }

  public Map<String, Param> params() {
    return params;
  }

  /** Rule tags plus one tag per supported platform, e.g. "gitlab-ci". */
  public String[] tags() {
    return Stream.concat(Stream.of(tags), platforms.stream().map(CiRule::platformTag)).toArray(String[]::new);
  }

  static String platformTag(Platform platform) {
    return switch (platform) {
      case GITHUB_ACTIONS -> "github-actions";
      case GITLAB_CI -> "gitlab-ci";
      case AZURE_PIPELINES -> "azure-pipelines";
    };
  }

  public SoftwareQuality quality() {
    return qualityOf(type);
  }

  static SoftwareQuality qualityOf(RuleType type) {
    return switch (type) {
      case BUG -> SoftwareQuality.RELIABILITY;
      case CODE_SMELL -> SoftwareQuality.MAINTAINABILITY;
      default -> SoftwareQuality.SECURITY;
    };
  }

  /** The tree check implementing the rule, or null for rules implemented directly by the sensor. */
  public CiCheck newCheck() {
    return check == null ? null : check.get();
  }
}
