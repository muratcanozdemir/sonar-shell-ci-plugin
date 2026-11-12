package io.github.shellci.ci.model;

import java.util.List;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/**
 * Script text executed by a CI step, possibly split over several YAML scalars that run in one shell session (GitLab
 * {@code before_script} + {@code script} items).
 *
 * @param parts   scalars in execution order
 * @param dialect "bash" or "sh" when the script runs in a POSIX shell, null otherwise (pwsh, cmd, unknown)
 */
public record ScriptNode(List<ScalarNode> parts, String dialect) {

  public static ScriptNode of(ScalarNode node, String dialect) {
    return new ScriptNode(List.of(node), dialect);
  }
}
