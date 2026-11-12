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
import org.junit.jupiter.api.Test;
import org.sonar.api.Plugin;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.SonarRuntime;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;
import org.sonar.api.server.rule.RulesDefinition;
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
