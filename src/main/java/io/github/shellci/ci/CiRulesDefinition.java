package io.github.shellci.ci;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.rule.RulesDefinition;

public class CiRulesDefinition implements RulesDefinition {

  public static final String REPOSITORY = "ci";

  @Override
  public void define(Context context) {
    NewRepository repository = context.createRepository(REPOSITORY, CiLanguage.KEY).setName("CI Pipelines");
    for (CiRule meta : CiRule.values()) {
      NewRule rule = repository.createRule(meta.key())
        .setName(meta.title())
        .setHtmlDescription(description(meta.key()))
        .setType(meta.type())
        .setSeverity(legacySeverity(meta.severity()))
        .addTags(meta.tags())
        .setActivatedByDefault(meta.defaultActive());
      if (meta.type() != RuleType.SECURITY_HOTSPOT) {
        rule.setCleanCodeAttribute(meta.attribute()).addDefaultImpact(meta.quality(), meta.severity());
      }
      if (meta.cwe().length > 0) {
        rule.addCwe(meta.cwe());
      }
      if (meta.owasp2021().length > 0) {
        rule.addOwaspTop10(OwaspTop10Version.Y2021, meta.owasp2021());
      }
      for (Map.Entry<String, CiRule.Param> param : meta.params().entrySet()) {
        rule.createParam(param.getKey())
          .setDefaultValue(param.getValue().defaultValue())
          .setDescription(param.getValue().description());
      }
      rule.setDebtRemediationFunction(rule.debtRemediationFunctions().constantPerIssue(
        meta.type() == RuleType.VULNERABILITY ? "15min" : "5min"));
    }
    repository.done();
  }

  static String legacySeverity(org.sonar.api.issue.impact.Severity severity) {
    return switch (severity) {
      case BLOCKER -> org.sonar.api.rule.Severity.BLOCKER;
      case HIGH -> org.sonar.api.rule.Severity.CRITICAL;
      case MEDIUM -> org.sonar.api.rule.Severity.MAJOR;
      case LOW -> org.sonar.api.rule.Severity.MINOR;
      case INFO -> org.sonar.api.rule.Severity.INFO;
    };
  }

  static String description(String key) {
    String resource = "/io/github/shellci/ci/rules/" + key + ".html";
    try (InputStream in = CiRulesDefinition.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing rule description " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
