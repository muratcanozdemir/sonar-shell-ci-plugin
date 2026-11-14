package io.github.shellci;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.shellci.ci.CiLanguage;
import io.github.shellci.ci.CiQualityProfile;
import io.github.shellci.ci.CiRulesDefinition;
import io.github.shellci.ci.CiRule;
import io.github.shellci.shell.ShellCheckCatalog;
import io.github.shellci.shell.ShellLanguage;
import io.github.shellci.shell.ShellQualityProfile;
import io.github.shellci.shell.ShellRulesDefinition;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sonar.api.Plugin;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.SonarRuntime;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.Version;

class DefinitionsTest {

  @Test
  void plugin_registers_all_extensions() {
    SonarRuntime runtime = SonarRuntimeImpl.forSonarQube(Version.create(26, 11), SonarQubeSide.SCANNER, SonarEdition.COMMUNITY);
    Plugin.Context context = new Plugin.Context(runtime);
    new ShellCiPlugin().define(context);
    assertThat(context.getExtensions()).hasSize(Settings.definitions().size() + 8);
  }

  @Test
  void shellcheck_rules() {
    RulesDefinition.Context context = new RulesDefinition.Context();
    new ShellRulesDefinition().define(context);
    RulesDefinition.Repository repository = context.repository(ShellRulesDefinition.REPOSITORY);
    assertThat(repository.language()).isEqualTo(ShellLanguage.KEY);
    assertThat(repository.rules()).hasSize(ShellCheckCatalog.entries().size()).hasSizeGreaterThan(400);

    RulesDefinition.Rule sc2086 = repository.rule("SC2086");
    assertThat(sc2086.name()).startsWith("SC2086: Double quote");
    assertThat(sc2086.htmlDescription()).contains("https://www.shellcheck.net/wiki/SC2086");
    assertThat(sc2086.activatedByDefault()).isTrue();
    assertThat(repository.rule("SC2164").type()).isEqualTo(RuleType.BUG);
  }

  @Test
  void former_shellcheck_plugin_rules_are_kept_as_deprecated() {
    RulesDefinition.Context context = new RulesDefinition.Context();
    new ShellRulesDefinition().define(context);
    RulesDefinition.Repository repository = context.repository(ShellRulesDefinition.REPOSITORY);
    for (String key : List.of("SC1013", "SC3058")) {
      RulesDefinition.Rule rule = repository.rule(key);
      assertThat(rule).as(key).isNotNull();
      assertThat(rule.status()).as(key).isEqualTo(RuleStatus.DEPRECATED);
      assertThat(rule.activatedByDefault()).as(key).isFalse();
      assertThat(rule.htmlDescription()).as(key).contains("no longer raise").doesNotContain("shellcheck.net/wiki");
    }
    assertThat(repository.rule("SC2039").status()).isEqualTo(RuleStatus.DEPRECATED); // wiki only
    assertThat(repository.rule("SC2086").status()).isEqualTo(RuleStatus.READY);
  }

  @Test
  void former_shellcheck_plugin_settings_are_honored() {
    // with the plugin's definitions, as in an analysis: a declared default would hide the former keys
    MapSettings settings = new MapSettings(new PropertyDefinitions(System2.INSTANCE, Settings.definitions()));
    assertThat(Settings.get(settings.asConfig(), Settings.SHELLCHECK_PATH)).isEmpty();
    assertThat(Settings.getBoolean(settings.asConfig(), Settings.SHELLCHECK_SKIP)).isFalse();

    settings.setProperty(Settings.LEGACY_SHELLCHECK_PATH, "/opt/shellcheck");
    settings.setProperty(Settings.LEGACY_SHELLCHECK_SKIP, "true");
    assertThat(Settings.get(settings.asConfig(), Settings.SHELLCHECK_PATH)).contains("/opt/shellcheck");
    assertThat(Settings.getBoolean(settings.asConfig(), Settings.SHELLCHECK_SKIP)).isTrue();

    // the current keys win; a blank value, as the former plugin's default path, counts as unset
    settings.setProperty(Settings.SHELLCHECK_PATH, "/usr/bin/shellcheck");
    settings.setProperty(Settings.SHELLCHECK_SKIP, "false");
    assertThat(Settings.get(settings.asConfig(), Settings.SHELLCHECK_PATH)).contains("/usr/bin/shellcheck");
    assertThat(Settings.getBoolean(settings.asConfig(), Settings.SHELLCHECK_SKIP)).isFalse();
    settings.setProperty(Settings.SHELLCHECK_PATH, " ");
    settings.setProperty(Settings.LEGACY_SHELLCHECK_PATH, "");
    assertThat(Settings.get(settings.asConfig(), Settings.SHELLCHECK_PATH)).isEmpty();
    assertThat(Settings.get(settings.asConfig(), Settings.SHELLCHECK_TIMEOUT)).contains("300");

    // declared (the scanner only exposes declared properties), hidden, and not deprecated keys (renamed silently)
    assertThat(Settings.definitions()).extracting(PropertyDefinition::deprecatedKey).allMatch(k -> k == null || k.isEmpty());
    assertThat(Settings.definitions()).filteredOn(d -> d.key().equals(Settings.LEGACY_SHELLCHECK_PATH)
        || d.key().equals(Settings.LEGACY_SHELLCHECK_SKIP))
      .hasSize(2).allMatch(PropertyDefinition::hidden);
  }

  @Test
  void ghactions_rules() {
    RulesDefinition.Context context = new RulesDefinition.Context();
    new CiRulesDefinition().define(context);
    RulesDefinition.Repository repository = context.repository(CiRulesDefinition.REPOSITORY);
    assertThat(repository.language()).isEqualTo(CiLanguage.KEY);
    assertThat(repository.rules()).hasSize(CiRule.values().length);
    for (RulesDefinition.Rule rule : repository.rules()) {
      assertThat(rule.htmlDescription()).as(rule.key()).isNotBlank();
      if (rule.type() == RuleType.SECURITY_HOTSPOT) {
        assertThat(rule.defaultImpacts()).as(rule.key()).isEmpty();
      }
    }
    RulesDefinition.Rule injection = repository.rule(CiRule.SCRIPT_INJECTION.key());
    assertThat(injection.type()).isEqualTo(RuleType.VULNERABILITY);
    assertThat(injection.securityStandards()).contains("cwe:94");
    assertThat(repository.rule(CiRule.UNPINNED_REFERENCE.key()).params()).hasSize(1);
  }

  @Test
  void quality_profiles() {
    BuiltInQualityProfilesDefinition.Context context = new BuiltInQualityProfilesDefinition.Context();
    new ShellQualityProfile().define(context);
    new CiQualityProfile().define(context);

    BuiltInQualityProfilesDefinition.BuiltInQualityProfile shell = context.profile(ShellLanguage.KEY, ShellQualityProfile.NAME);
    assertThat(shell.isDefault()).isTrue();
    assertThat(shell.rules()).hasSize((int) ShellCheckCatalog.entries().stream().filter(ShellCheckCatalog.Entry::defaultActive).count());

    BuiltInQualityProfilesDefinition.BuiltInQualityProfile gha = context.profile(CiLanguage.KEY, ShellQualityProfile.NAME);
    assertThat(gha.rules()).hasSize((int) Arrays.stream(CiRule.values()).filter(CiRule::defaultActive).count());
  }

  @Test
  void languages() {
    MapSettings settings = new MapSettings();
    assertThat(new ShellLanguage(settings.asConfig()).getFileSuffixes()).containsExactly(".sh", ".bash", ".ksh", ".dash");
    assertThat(new CiLanguage().getFileSuffixes()).isEmpty();
    assertThat(new CiLanguage().filenamePatterns()).isEmpty();

    settings.setProperty(Settings.SHELL_SUFFIXES, ".zsh, .sh");
    assertThat(new ShellLanguage(settings.asConfig()).getFileSuffixes()).containsExactly(".zsh", ".sh");
  }
}
