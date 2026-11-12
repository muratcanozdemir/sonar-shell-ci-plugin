package io.github.shellci;

import io.github.shellci.ci.Platform;
import java.util.ArrayList;
import java.util.List;
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

  private Settings() {
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
        .description("Path to the ShellCheck executable used during analysis. When it cannot be run, shell files only get metrics and the "
          + "reports given in " + SHELLCHECK_REPORT_PATHS + ", and scripts in CI pipeline files are not checked.")
        .defaultValue(SHELLCHECK_PATH_DEFAULT)
        .category(CATEGORY).subCategory("Shell")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(SHELLCHECK_SKIP)
        .name("Do not run ShellCheck")
        .description("Never execute ShellCheck. Shell files only get the reports given in " + SHELLCHECK_REPORT_PATHS
          + "; scripts in CI pipeline files are not checked.")
        .defaultValue("false")
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
