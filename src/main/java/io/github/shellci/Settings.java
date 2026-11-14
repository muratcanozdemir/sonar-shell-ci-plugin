package io.github.shellci;

import io.github.shellci.ci.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;

/** All analysis properties exposed by the plugin. */
public final class Settings {

  public static final String CATEGORY = "Shell & CI";

  public static final String SHELL_SUFFIXES = "sonar.shell.file.suffixes";
  public static final String SHELL_SUFFIXES_DEFAULT = ".sh,.bash,.ksh,.dash";

  public static final String SHELLCHECK_PATH = "sonar.shellcheck.path";
  public static final String SHELLCHECK_PATH_DEFAULT = "shellcheck";
  public static final String SHELLCHECK_SKIP = "sonar.shellcheck.skip";
  public static final String SHELLCHECK_REPORT_PATHS = "sonar.shellcheck.reportPaths";
  public static final String SHELLCHECK_TIMEOUT = "sonar.shellcheck.timeoutSeconds";

  /** Keys of the former ShellCheck plugin (sonar-shellcheck, plugin key "shellcheck"), still honored so it can be replaced as is. */
  public static final String LEGACY_SHELLCHECK_PATH = "sonar.shellcheck.shellcheck.path";
  public static final String LEGACY_SHELLCHECK_SKIP = "sonar.shell.skip";
  private static final Map<String, String> LEGACY_KEYS = Map.of(
    SHELLCHECK_PATH, LEGACY_SHELLCHECK_PATH,
    SHELLCHECK_SKIP, LEGACY_SHELLCHECK_SKIP);

  private static final Logger LOG = LoggerFactory.getLogger(Settings.class);

  private Settings() {
  }

  /** Former keys already reported in this analysis (both sensors read the settings). */
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  /**
   * Non-blank value of a property, or else of the former ShellCheck plugin's key it replaces, with a deprecation
   * warning logged once. A blank value counts as unset: the former plugin used an empty path for "shellcheck on the
   * PATH". The two current keys therefore declare no default value (it would always win over the former key); callers
   * apply the defaults. The former keys are declared as hidden properties (see {@link #definitions()}), not as
   * deprecated keys of the current ones, which the scanner would rename silently, without telling users to update their
   * configuration.
   */
  public static Optional<String> get(Configuration config, String key) {
    Optional<String> value = config.get(key).map(String::trim).filter(v -> !v.isEmpty());
    String legacyKey = LEGACY_KEYS.get(key);
    if (value.isPresent() || legacyKey == null) {
      return value;
    }
    Optional<String> legacy = config.get(legacyKey).map(String::trim).filter(v -> !v.isEmpty());
    if (legacy.isPresent() && WARNED.add(legacyKey)) {
      LOG.warn("Property '{}' is deprecated, use '{}' instead", legacyKey, key);
    }
    return legacy;
  }

  public static boolean getBoolean(Configuration config, String key) {
    return get(config, key).map(Boolean::parseBoolean).orElse(false);
  }

  public static List<PropertyDefinition> definitions() {
    List<PropertyDefinition> definitions = new ArrayList<>(List.of(
      PropertyDefinition.builder(SHELL_SUFFIXES)
        .name("Shell file suffixes")
        .description("Comma-separated list of suffixes of shell script files to analyze.")
        .defaultValue(SHELL_SUFFIXES_DEFAULT)
        .multiValues(true)
        .category(CATEGORY).subCategory("Shell")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(SHELLCHECK_PATH)
        .name("ShellCheck executable")
        .description("Path to the ShellCheck executable used during analysis (default: '" + SHELLCHECK_PATH_DEFAULT
          + "' on the PATH). When it cannot be run, shell files only get metrics and the reports given in "
          + SHELLCHECK_REPORT_PATHS + ", and scripts in CI pipeline files are not checked.")
        .category(CATEGORY).subCategory("Shell")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(SHELLCHECK_SKIP)
        .name("Do not run ShellCheck")
        .description("Never execute ShellCheck. Shell files only get the reports given in " + SHELLCHECK_REPORT_PATHS
          + "; scripts in CI pipeline files are not checked.")
        .type(org.sonar.api.PropertyType.BOOLEAN)
        .category(CATEGORY).subCategory("Shell")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(SHELLCHECK_REPORT_PATHS)
        .name("ShellCheck report paths")
        .description("Comma-separated paths to ShellCheck reports produced with '-f json1' or '-f json'.")
        .multiValues(true)
        .category(CATEGORY).subCategory("Shell")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(SHELLCHECK_TIMEOUT)
        .name("ShellCheck timeout")
        .description("Maximum time in seconds a single ShellCheck invocation may take.")
        .defaultValue("300")
        .type(org.sonar.api.PropertyType.INTEGER)
        .category(CATEGORY).subCategory("Shell")
        .onQualifiers(Qualifiers.PROJECT)
        .build()));
    LEGACY_KEYS.forEach((key, legacyKey) -> definitions.add(PropertyDefinition.builder(legacyKey)
      .name(legacyKey + " (deprecated)")
      .description("Former ShellCheck plugin property, still honored. Deprecated: use " + key + ".")
      .hidden()
      .build()));
    for (Platform platform : Platform.values()) {
      definitions.add(PropertyDefinition.builder(platform.patternsProperty())
        .name(platform.displayName() + " file patterns")
        .description("Comma-separated path patterns identifying " + platform.displayName()
          + " pipeline files.")
        .defaultValue(platform.defaultPatterns())
        .multiValues(true)
        .category(CATEGORY).subCategory("CI Pipelines")
        .onQualifiers(Qualifiers.PROJECT)
        .build());
    }
    return definitions;
  }
}
