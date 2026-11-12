package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/**
 * "Pwn request": a workflow triggered by {@code pull_request_target} or {@code workflow_run} runs with secrets and a
 * write token, so checking out and running the pull request's code hands both to the PR author.
 */
public class UntrustedCheckoutCheck implements CiCheck {

  private static final Set<String> PRIVILEGED_TRIGGERS = Set.of("pull_request_target", "workflow_run");

  private static final Pattern UNTRUSTED_REF = Pattern.compile(String.join("|",
    "github\\.event\\.pull_request\\.head\\.(?:sha|ref|repo)",
    "github\\.event\\.pull_request\\.merge_commit_sha",
    "github\\.head_ref",
    "github\\.event\\.workflow_run\\.head_(?:sha|branch|repository)",
    "refs/pull/"),
    Pattern.CASE_INSENSITIVE);

  private static final Pattern UNTRUSTED_SHELL_CHECKOUT = Pattern.compile(String.join("|",
    "\\bgh\\s+pr\\s+checkout\\b",
    "\\bgit\\s+(?:fetch|pull)\\b[^\\n]*\\bpull/",
    "\\bgit\\s+(?:checkout|switch|fetch|pull)\\b[^\\n]*\\$\\{\\{\\s*github\\.(?:head_ref|event\\.pull_request\\.head)"),
    Pattern.CASE_INSENSITIVE);

  @Override
  public void check(CiFile file, Context context) {
    Optional<Map.Entry<String, Node>> trigger = GitHub.triggers(file).entrySet().stream()
      .filter(e -> PRIVILEGED_TRIGGERS.contains(e.getKey()))
      .findFirst();
    if (trigger.isEmpty()) {
      return;
    }
    List<Secondary> secondary = List.of(new Secondary(file.span(trigger.get().getValue()),
      "Privileged trigger: the workflow runs with repository secrets and a write token."));
    String event = trigger.get().getKey();

    for (GitHub.Step step : GitHub.steps(file)) {
      Optional<String> action = GitHub.actionName(step.body());
      if (action.isPresent() && action.get().equals("actions/checkout")) {
        Optional<Node> with = Yaml.get(step.body(), "with");
        for (String input : List.of("ref", "repository")) {
          with.flatMap(w -> Yaml.get(w, input))
            .filter(v -> v instanceof ScalarNode s && UNTRUSTED_REF.matcher(s.getValue()).find())
            .ifPresent(v -> context.report(file.span(v),
              "Do not check out untrusted pull request code in a \"" + event + "\" workflow.", secondary));
        }
      }
      Yaml.get(step.body(), "run")
        .filter(ScalarNode.class::isInstance)
        .map(ScalarNode.class::cast)
        .ifPresent(run -> file.find(run, UNTRUSTED_SHELL_CHECKOUT).forEach(m -> context.report(m.span(),
          "Do not check out untrusted pull request code in a \"" + event + "\" workflow.", secondary)));
    }
  }
}
