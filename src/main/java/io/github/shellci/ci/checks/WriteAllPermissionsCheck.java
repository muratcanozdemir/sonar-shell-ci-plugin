package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** {@code permissions: write-all} grants the GITHUB_TOKEN write access to every scope. */
public class WriteAllPermissionsCheck implements CiCheck {

  @Override
  public void check(CiFile file, Context context) {
    if (!GitHub.applies(file)) {
      return;
    }
    Yaml.get(file.root(), "permissions").ifPresent(p -> check(file, p, context));
    for (GitHub.Job job : GitHub.jobs(file)) {
      Yaml.get(job.body(), "permissions").ifPresent(p -> check(file, p, context));
    }
  }

  private static void check(CiFile file, Node permissions, Context context) {
    if (permissions instanceof ScalarNode s && "write-all".equals(s.getValue().trim())) {
      context.report(file.span(s), "Make sure granting write access to every scope is necessary; list only the scopes this needs.");
    }
  }
}
