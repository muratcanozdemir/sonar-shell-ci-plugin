package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;

/** Without a {@code permissions} block the GITHUB_TOKEN gets the repository/organization defaults, often read-write. */
public class MissingPermissionsCheck implements CiCheck {

  @Override
  public void check(CiFile file, Context context) {
    if (!GitHub.isWorkflow(file) || Yaml.get(file.root(), "permissions").isPresent()) {
      return;
    }
    for (GitHub.Job job : GitHub.jobs(file)) {
      if (Yaml.get(job.body(), "permissions").isEmpty()) {
        context.report(file.span(job.key()),
          "Make sure the default GITHUB_TOKEN permissions are safe for job \"" + job.id()
            + "\"; declare \"permissions\" for it or at the workflow level.");
      }
    }
  }
}
