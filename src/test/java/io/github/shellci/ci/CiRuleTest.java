package io.github.shellci.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.sonar.api.issue.impact.Severity;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rules.RuleType;

class CiRuleTest {

  @Test
  void quality_by_type() {
    assertThat(CiRule.qualityOf(RuleType.BUG)).isEqualTo(SoftwareQuality.RELIABILITY);
    assertThat(CiRule.qualityOf(RuleType.CODE_SMELL)).isEqualTo(SoftwareQuality.MAINTAINABILITY);
    assertThat(CiRule.qualityOf(RuleType.VULNERABILITY)).isEqualTo(SoftwareQuality.SECURITY);
  }

  @Test
  void legacy_severities() {
    assertThat(Arrays.stream(Severity.values()).map(CiRulesDefinition::legacySeverity))
      .containsExactly("INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER");
  }

  @Test
  void every_rule_has_a_description_and_platform_tags() {
    for (CiRule rule : CiRule.values()) {
      assertThat(CiRulesDefinition.description(rule.key())).as(rule.key()).contains("<h2>");
      assertThat(rule.tags()).as(rule.key()).containsAnyOf("github-actions", "gitlab-ci", "azure-pipelines");
      assertThat(rule.platforms()).as(rule.key()).isNotEmpty();
    }
    assertThatThrownBy(() -> CiRulesDefinition.description("NoSuchRule")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void descriptions_list_exactly_the_rule_cwes() {
    Pattern cwe = Pattern.compile("CWE-(\\d+)");
    for (CiRule rule : CiRule.values()) {
      Matcher m = cwe.matcher(CiRulesDefinition.description(rule.key()));
      List<Integer> listed = m.results().map(r -> Integer.parseInt(r.group(1))).sorted().toList();
      assertThat(listed).as(rule.key()).containsExactlyElementsOf(Arrays.stream(rule.cwe()).sorted().boxed().toList());
    }
  }

  /** README "CI pipelines" table: one row per rule with its type, platforms and default activation. */
  @Test
  void readme_rule_table_matches_the_metadata() throws IOException {
    Pattern row = Pattern.compile("^\\| (.*\\(`(\\w+)`(, off by default)?\\)) \\| ([\\w ]+) \\|(.*)\\|$", Pattern.MULTILINE);
    Map<String, MatchResult> rows = new HashMap<>();
    row.matcher(Files.readString(Path.of("README.md"))).results().forEach(r -> rows.put(r.group(2), r));
    assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(Arrays.stream(CiRule.values()).map(CiRule::key).toList());
    for (CiRule rule : CiRule.values()) {
      MatchResult r = rows.get(rule.key());
      String type = switch (rule.type()) {
        case BUG -> "Bug";
        case VULNERABILITY -> "Vulnerability";
        case SECURITY_HOTSPOT -> "Security Hotspot";
        case CODE_SMELL -> "Code Smell";
      };
      assertThat(r.group(4)).as(rule.key()).isEqualTo(type);
      String[] cells = r.group(5).split("\\|", -1);
      assertThat(Arrays.stream(Platform.values()).filter(p -> cells[p.ordinal()].contains("✓")).collect(Collectors.toSet()))
        .as(rule.key()).isEqualTo(rule.platforms());
      assertThat(r.group(3) == null).as(rule.key() + " active by default").isEqualTo(rule.defaultActive());
    }
  }
}
