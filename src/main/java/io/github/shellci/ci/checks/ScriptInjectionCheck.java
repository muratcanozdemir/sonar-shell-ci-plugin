package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.Azure;
import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.ScriptNode;
import io.github.shellci.ci.model.Yaml;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/**
 * Attacker-controlled values pasted into script text by the CI system before the script runs: GitHub {@code ${{ }}}
 * expressions and Azure Pipelines {@code $(macro)}, {@code ${{ }}} and {@code $[ ]} expressions.
 */
public class ScriptInjectionCheck implements CiCheck {

  @Override
  public void check(CiFile file, Context context) {
    if (GitHub.applies(file)) {
      checkGitHub(file, context);
    } else if (Azure.applies(file)) {
      checkAzure(file, context);
    }
  }

  private static void checkGitHub(CiFile file, Context context) {
    for (GitHub.Step step : GitHub.steps(file)) {
      Yaml.get(step.body(), "run").ifPresent(run -> scanGitHub(file, run, "script", context));
      Optional<String> action = GitHub.actionName(step.body());
      if (action.isPresent() && action.get().equals("actions/github-script")) {
        Yaml.get(step.body(), "with").flatMap(w -> Yaml.get(w, "script"))
          .ifPresent(script -> scanGitHub(file, script, "JavaScript code", context));
      }
    }
  }

  private static void scanGitHub(CiFile file, Node node, String what, Context context) {
    if (!(node instanceof ScalarNode scalar)) {
      return;
    }
    for (CiFile.Match expression : file.find(scalar, Expressions.EXPRESSION)) {
      Matcher untrusted = Expressions.UNTRUSTED_INPUT.matcher(expression.text());
      if (untrusted.find()) {
        context.report(expression.span(),
          "\"" + untrusted.group() + "\" is attacker-controlled and is expanded directly into this " + what
            + ". Pass it through an environment variable instead.");
      }
    }
  }

  private static void checkAzure(CiFile file, Context context) {
    for (Azure.Step step : Azure.steps(file)) {
      for (ScriptNode script : Azure.scripts(step)) {
        for (ScalarNode part : script.parts()) {
          for (Pattern syntax : file.platform().expressions()) {
            for (CiFile.Match expression : file.find(part, syntax)) {
              Matcher untrusted = Expressions.AZURE_UNTRUSTED_VARIABLE.matcher(expression.text());
              if (untrusted.find()) {
                String variable = untrusted.group();
                context.report(expression.span(),
                  "\"" + variable + "\" is attacker-controlled and is expanded directly into this script. Read the "
                    + "environment variable " + envName(variable) + " instead.");
              }
            }
          }
        }
      }
    }
  }

  /** Azure exposes Build.SourceBranch as BUILD_SOURCEBRANCH in the step environment. */
  private static String envName(String variable) {
    return variable.toUpperCase(Locale.ROOT).replace('.', '_');
  }
}
