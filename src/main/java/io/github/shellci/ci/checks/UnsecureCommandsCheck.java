package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;
import java.util.Locale;
import org.snakeyaml.engine.v2.nodes.NodeTuple;

/** Re-enabling the deprecated {@code set-env}/{@code add-path} workflow commands allows environment injection via logs. */
public class UnsecureCommandsCheck implements CiCheck {

  private static final String VARIABLE = "ACTIONS_ALLOW_UNSECURE_COMMANDS";

  @Override
  public void check(CiFile file, Context context) {
    if (!GitHub.applies(file)) {
      return;
    }
    Yaml.visitMappings(file.root(), mapping -> {
      for (NodeTuple tuple : mapping.getValue()) {
        if (VARIABLE.equals(Yaml.keyName(tuple))
          && Yaml.scalar(tuple.getValueNode()).map(v -> v.trim().toLowerCase(Locale.ROOT).equals("true")).orElse(false)) {
          context.report(file.span(tuple.getKeyNode()),
            "Remove this variable; it re-enables the insecure \"set-env\" and \"add-path\" workflow commands.");
        }
      }
    });
  }
}
