package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.Yaml;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/**
 * Literal values assigned to secret-named variables in {@code env:} (GitHub) and {@code variables:} (GitLab, Azure)
 * blocks. Values that reference a variable, secret or expression are fine.
 */
public class HardcodedSecretCheck implements CiCheck {

  private static final Pattern SECRET_NAME = Pattern.compile(
    "passw(?:or)?d|\\bpwd\\b|secret|token|api_?key|access_?key|private_?key|credential",
    Pattern.CASE_INSENSITIVE);
  /** Names that hold a reference to a secret, not the secret itself. */
  private static final Pattern REFERENCE_NAME = Pattern.compile(
    "(?:_|\\b)(?:url|uri|endpoint|path|file|name|id|type|header|length|expiry|expires|ttl|enabled|required)$",
    Pattern.CASE_INSENSITIVE);
  private static final Set<String> NOT_SECRETS = Set.of("true", "false", "yes", "no", "on", "off", "null", "none", "");

  @Override
  public void check(CiFile file, Context context) {
    if (file.root() == null) {
      return;
    }
    Yaml.visitMappings(file.root(), mapping -> {
      for (NodeTuple tuple : mapping.getValue()) {
        String key = Yaml.keyName(tuple);
        if (key.equals("env") || key.equals("variables")) {
          checkVariables(file, tuple.getValueNode(), context);
        }
      }
    });
  }

  private static void checkVariables(CiFile file, Node variables, Context context) {
    // name: value, or GitLab name: {value: ...}
    for (NodeTuple variable : Yaml.entries(variables)) {
      Node value = variable.getValueNode();
      if (!(value instanceof ScalarNode)) {
        value = Yaml.get(value, "value").orElse(null);
      }
      check(file, Yaml.keyName(variable), value, context);
    }
    // Azure list form: - name: x \n value: y
    for (Node item : Yaml.items(variables)) {
      Yaml.scalar(item, "name").ifPresent(name -> check(file, name, Yaml.get(item, "value").orElse(null), context));
    }
  }

  private static void check(CiFile file, String name, Node value, Context context) {
    if (!(value instanceof ScalarNode scalar) || !SECRET_NAME.matcher(name).find() || REFERENCE_NAME.matcher(name).find()) {
      return;
    }
    String text = scalar.getValue().trim();
    if (NOT_SECRETS.contains(text.toLowerCase(Locale.ROOT)) || text.length() < 6 || text.contains("$")
      || text.matches("\\d+") || text.startsWith("/") || text.startsWith("./")) {
      return;
    }
    context.report(file.span(scalar), "Make sure this value of \"" + name
      + "\" is not a secret; store secrets in the CI system's secret store and reference them instead.");
  }
}
