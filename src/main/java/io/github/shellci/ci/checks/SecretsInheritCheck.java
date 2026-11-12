package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** {@code secrets: inherit} passes every secret of the caller to a reusable workflow from another repository. */
public class SecretsInheritCheck implements CiCheck {

  @Override
  public void check(CiFile file, Context context) {
    for (GitHub.Job job : GitHub.jobs(file)) {
      boolean external = Yaml.scalar(job.body(), "uses").map(u -> !u.trim().startsWith("./")).orElse(false);
      if (!external) {
        continue;
      }
      Yaml.get(job.body(), "secrets")
        .filter(s -> s instanceof ScalarNode n && "inherit".equals(n.getValue().trim()))
        .ifPresent(s -> context.report(file.span(s),
          "Make sure this external workflow needs every secret; pass only the required ones explicitly."));
    }
  }
}
