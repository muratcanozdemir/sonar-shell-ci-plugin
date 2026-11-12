package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.Azure;
import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;
import java.util.Locale;
import java.util.Optional;
import org.snakeyaml.engine.v2.nodes.NodeTuple;

/**
 * Checkout steps that leave the job token in the repository's git configuration: actions/checkout unless
 * {@code persist-credentials: false}, Azure Pipelines {@code checkout} with {@code persistCredentials: true}.
 */
public class CheckoutPersistCredentialsCheck implements CiCheck {

  @Override
  public void check(CiFile file, Context context) {
    for (GitHub.Step step : GitHub.steps(file)) {
      if (!GitHub.actionName(step.body()).map("actions/checkout"::equals).orElse(false)) {
        continue;
      }
      boolean disabled = Yaml.get(step.body(), "with")
        .flatMap(w -> Yaml.scalar(w, "persist-credentials"))
        .map(v -> isBoolean(v, "false"))
        .orElse(false);
      if (!disabled) {
        Yaml.get(step.body(), "uses").ifPresent(uses -> context.report(file.span(uses),
          "Make sure persisting the GITHUB_TOKEN in the checked-out repository is safe here, or set \"persist-credentials: false\"."));
      }
    }
    for (Azure.Step step : Azure.steps(file)) {
      if (Yaml.get(step.body(), "checkout").isEmpty()) {
        continue;
      }
      Optional<NodeTuple> persist = Yaml.entry(step.body(), "persistCredentials");
      if (persist.isPresent() && Yaml.scalar(persist.get().getValueNode()).map(v -> isBoolean(v, "true")).orElse(false)) {
        context.report(file.span(persist.get().getKeyNode()),
          "Make sure persisting the System.AccessToken in the checked-out repository is safe here.");
      }
    }
  }

  private static boolean isBoolean(String value, String expected) {
    return value.trim().toLowerCase(Locale.ROOT).equals(expected);
  }
}
