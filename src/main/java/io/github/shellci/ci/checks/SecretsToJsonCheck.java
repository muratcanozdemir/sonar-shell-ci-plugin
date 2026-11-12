package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.Yaml;
import java.util.regex.Pattern;

/** {@code toJSON(secrets)} serializes every secret available to the job into one value. */
public class SecretsToJsonCheck implements CiCheck {

  private static final Pattern TO_JSON_SECRETS = Pattern.compile("\\btojson\\(\\s*secrets\\s*\\)", Pattern.CASE_INSENSITIVE);

  @Override
  public void check(CiFile file, Context context) {
    if (!GitHub.applies(file)) {
      return;
    }
    Yaml.visitScalars(file.root(), scalar -> {
      if (TO_JSON_SECRETS.matcher(scalar.getValue()).find()) {
        file.find(scalar, TO_JSON_SECRETS).forEach(m -> context.report(m.span(),
          "Reference only the secrets this needs instead of serializing all of them."));
      }
    });
  }
}
