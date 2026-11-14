package io.github.shellci.shell;

import org.sonar.api.issue.impact.Severity;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rules.CleanCodeAttribute;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.rule.RulesDefinition;

public class ShellRulesDefinition implements RulesDefinition {

  public static final String REPOSITORY = "shellcheck";

  @Override
  public void define(Context context) {
    NewRepository repository = context.createRepository(REPOSITORY, ShellLanguage.KEY).setName("ShellCheck");
    for (ShellCheckCatalog.Entry entry : ShellCheckCatalog.entries()) {
      NewRule rule = repository.createRule(entry.key())
        .setName(entry.name())
        .setHtmlDescription(description(entry))
        .addTags("shellcheck")
        .setActivatedByDefault(entry.defaultActive());
      rule.setDebtRemediationFunction(rule.debtRemediationFunctions().constantPerIssue("5min"));
      switch (entry.level()) {
        case "error" -> rule.setType(RuleType.BUG)
          .setSeverity(org.sonar.api.rule.Severity.MAJOR)
          .setCleanCodeAttribute(CleanCodeAttribute.LOGICAL)
          .addDefaultImpact(SoftwareQuality.RELIABILITY, Severity.HIGH);
        case "warning" -> rule.setType(RuleType.BUG)
          .setSeverity(org.sonar.api.rule.Severity.MAJOR)
          .setCleanCodeAttribute(CleanCodeAttribute.LOGICAL)
          .addDefaultImpact(SoftwareQuality.RELIABILITY, Severity.MEDIUM);
        case "info" -> rule.setType(RuleType.CODE_SMELL)
          .setSeverity(org.sonar.api.rule.Severity.MINOR)
          .setCleanCodeAttribute(CleanCodeAttribute.CLEAR)
          .addDefaultImpact(SoftwareQuality.MAINTAINABILITY, Severity.LOW);
        default -> rule.setType(RuleType.CODE_SMELL)
          .setSeverity(org.sonar.api.rule.Severity.INFO)
          .setCleanCodeAttribute(CleanCodeAttribute.CONVENTIONAL)
          .addDefaultImpact(SoftwareQuality.MAINTAINABILITY, Severity.INFO);
      }
      if (entry.retired()) {
        // Documented in the wiki or defined by the former ShellCheck plugin, but no longer emitted by ShellCheck.
        rule.setStatus(RuleStatus.DEPRECATED);
      }
    }
    repository.done();
  }

  private static String description(ShellCheckCatalog.Entry entry) {
    StringBuilder html = new StringBuilder()
      .append("<p>This issue is raised by <a href=\"https://www.shellcheck.net\">ShellCheck</a> check <code>")
      .append(entry.key())
      .append("</code> (ShellCheck level: <em>").append(entry.level()).append("</em>).</p>");
    if (entry.wiki()) {
      html.append("<p>See <a href=\"https://www.shellcheck.net/wiki/").append(entry.key())
        .append("\">the ShellCheck wiki page for ").append(entry.key())
        .append("</a> for problematic code, the correct alternative, rationale and known exceptions.</p>");
    }
    if (entry.retired()) {
      html.append("<p>Current ShellCheck releases no longer raise this issue. The rule is kept so that existing issues and quality ")
        .append("profiles keep referring to it.</p>");
    }
    html.append("<p>To suppress a single occurrence, add <code># shellcheck disable=")
      .append(entry.key()).append("</code> on the line before the command.</p>");
    return html.toString();
  }
}
