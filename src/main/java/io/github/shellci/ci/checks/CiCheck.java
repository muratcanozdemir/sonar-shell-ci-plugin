package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.Span;
import io.github.shellci.ci.model.CiFile;
import java.util.List;

/** A rule implemented on the parsed workflow tree. Implementations are stateless. */
public interface CiCheck {

  void check(CiFile file, Context context);

  record Secondary(Span span, String message) {
  }

  interface Context {
    void report(Span primary, String message, List<Secondary> secondaries);

    default void report(Span primary, String message) {
      report(primary, message, List.of());
    }

    /** Value of a rule parameter, or the default declared in the rule metadata. */
    String param(String key);
  }
}
