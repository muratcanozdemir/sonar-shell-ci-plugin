package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.GitLab;
import io.github.shellci.ci.model.Yaml;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/**
 * Pipeline code pulled from elsewhere must be referenced immutably: GitHub actions and reusable workflows by commit
 * SHA, GitLab includes from other projects and CI/CD components by commit SHA, and never from a plain URL. Docker
 * images run as steps ({@code uses: docker://...}) are left to {@link UnpinnedImageCheck}.
 */
public class UnpinnedReferenceCheck implements CiCheck {

  public static final String TRUSTED_OWNERS = "trustedOwners";
  private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");

  @Override
  public void check(CiFile file, Context context) {
    Set<String> trusted = Arrays.stream(context.param(TRUSTED_OWNERS).split(","))
      .map(s -> s.trim().toLowerCase(Locale.ROOT))
      .filter(s -> !s.isEmpty())
      .collect(Collectors.toSet());
    for (GitHub.Job job : GitHub.jobs(file)) {
      Yaml.get(job.body(), "uses").ifPresent(uses -> checkGitHub(file, uses, trusted, context, "reusable workflow"));
    }
    for (GitHub.Step step : GitHub.steps(file)) {
      Yaml.get(step.body(), "uses").ifPresent(uses -> checkGitHub(file, uses, trusted, context, "action"));
    }
    for (Node include : GitLab.includes(file)) {
      checkGitLabInclude(file, include, trusted, context);
    }
  }

  private static void checkGitHub(CiFile file, Node node, Set<String> trusted, Context context, String what) {
    if (!(node instanceof ScalarNode scalar)) {
      return;
    }
    String uses = scalar.getValue().trim();
    if (uses.startsWith("./") || uses.contains("${{") || uses.startsWith("docker://")) {
      return; // local action/workflow, dynamic reference, or container image (see UnpinnedImageCheck)
    }
    if (trusted.contains(owner(uses))) {
      return;
    }
    int at = uses.lastIndexOf('@');
    if (at < 0) {
      context.report(file.span(scalar), "Reference this " + what + " by a full-length commit SHA.");
    } else if (!COMMIT_SHA.matcher(uses.substring(at + 1)).matches()) {
      context.report(file.span(scalar), "Make sure using a mutable reference (\"" + uses.substring(at + 1)
        + "\") for this " + what + " is safe here; pin it to a full-length commit SHA.");
    }
  }

  private static void checkGitLabInclude(CiFile file, Node include, Set<String> trusted, Context context) {
    if (include instanceof ScalarNode s) {
      if (isUrl(s.getValue())) {
        reportRemote(file, s, context);
      }
      return; // a plain string is otherwise a local file
    }
    Optional<NodeTuple> remote = Yaml.entry(include, "remote");
    if (remote.isPresent()) {
      reportRemote(file, remote.get().getValueNode(), context);
      return;
    }
    Optional<NodeTuple> project = Yaml.entry(include, "project");
    if (project.isPresent()) {
      String path = Yaml.scalar(project.get().getValueNode()).orElse("");
      if (trusted.contains(owner(path))) {
        return;
      }
      Optional<NodeTuple> ref = Yaml.entry(include, "ref");
      if (ref.isEmpty()) {
        context.report(file.span(project.get().getKeyNode()),
          "Make sure including this project's default branch is safe here; set \"ref\" to a full-length commit SHA.");
      } else if (!Yaml.scalar(ref.get().getValueNode()).map(r -> COMMIT_SHA.matcher(r.trim()).matches()).orElse(true)) {
        context.report(file.span(ref.get().getValueNode()),
          "Make sure including a mutable reference is safe here; set \"ref\" to a full-length commit SHA.");
      }
      return;
    }
    Yaml.entry(include, "component").ifPresent(component -> Yaml.scalar(component.getValueNode()).ifPresent(value -> {
      String path = value.replaceFirst("^[^/]+/", ""); // drop the instance host ($CI_SERVER_FQDN or gitlab.com)
      if (trusted.contains(owner(path)) || value.contains("$[[")) {
        return;
      }
      int at = value.lastIndexOf('@');
      String version = at < 0 ? "" : value.substring(at + 1);
      if (!COMMIT_SHA.matcher(version).matches()) {
        context.report(file.span(component.getValueNode()), "Make sure using a mutable version (\""
          + (version.isEmpty() ? "default" : version) + "\") of this component is safe here; pin it to a full-length commit SHA.");
      }
    }));
  }

  private static void reportRemote(CiFile file, Node node, Context context) {
    context.report(file.span(node),
      "Make sure including configuration from this URL is safe here; nothing guarantees its content does not change.");
  }

  private static boolean isUrl(String value) {
    String v = value.trim().toLowerCase(Locale.ROOT);
    return v.startsWith("https://") || v.startsWith("http://");
  }

  private static String owner(String path) {
    return path.trim().split("/", 2)[0].toLowerCase(Locale.ROOT);
  }
}
