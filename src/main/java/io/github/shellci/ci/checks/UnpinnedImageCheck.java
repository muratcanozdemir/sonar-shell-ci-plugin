package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.Azure;
import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.GitHub;
import io.github.shellci.ci.model.GitLab;
import io.github.shellci.ci.model.Yaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** Job, service and step ({@code uses: docker://...}) container images referenced by a mutable tag. */
public class UnpinnedImageCheck implements CiCheck {

  public static final String REQUIRE_DIGEST = "requireDigest";
  private static final String DOCKER_SCHEME = "docker://";

  @Override
  public void check(CiFile file, Context context) {
    boolean requireDigest = Boolean.parseBoolean(context.param(REQUIRE_DIGEST).trim());
    for (ScalarNode image : images(file)) {
      String value = image.getValue().trim();
      if (value.isEmpty() || value.contains("$") || value.contains("@sha256:")) {
        continue; // variable/expression, or already pinned
      }
      String tag = tag(value.startsWith(DOCKER_SCHEME) ? value.substring(DOCKER_SCHEME.length()) : value);
      if (tag == null || tag.equalsIgnoreCase("latest")) {
        context.report(file.span(image), "Make sure using " + (tag == null ? "the implicit \"latest\" tag" : "the \"latest\" tag")
          + " of this image is safe here; pin it to a version, ideally a digest (\"@sha256:...\").");
      } else if (requireDigest) {
        context.report(file.span(image), "Make sure using the mutable tag \"" + tag + "\" of this image is safe here; pin it to a digest (\"@sha256:...\").");
      }
    }
  }

  private static List<ScalarNode> images(CiFile file) {
    List<ScalarNode> images = new ArrayList<>(Azure.images(file));
    images.addAll(GitLab.images(file));
    for (GitHub.Job job : GitHub.jobs(file)) {
      Yaml.get(job.body(), "container").ifPresent(c -> addGitHubImage(c, images));
      Yaml.get(job.body(), "services").ifPresent(services -> {
        for (NodeTuple service : Yaml.entries(services)) {
          addGitHubImage(service.getValueNode(), images);
        }
      });
    }
    for (GitHub.Step step : GitHub.steps(file)) {
      Yaml.get(step.body(), "uses")
        .filter(u -> u instanceof ScalarNode s && s.getValue().trim().startsWith(DOCKER_SCHEME))
        .map(ScalarNode.class::cast)
        .ifPresent(images::add);
    }
    return images;
  }

  private static void addGitHubImage(Node node, List<ScalarNode> images) {
    if (node instanceof ScalarNode s) {
      images.add(s);
    } else {
      Yaml.get(node, "image").filter(ScalarNode.class::isInstance).map(ScalarNode.class::cast).ifPresent(images::add);
    }
  }

  /** "registry:5000/team/app:1.2" has tag "1.2"; "registry:5000/team/app" has none. */
  static String tag(String image) {
    String withoutDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
    int colon = withoutDigest.lastIndexOf(':');
    int slash = withoutDigest.lastIndexOf('/');
    return colon > slash ? withoutDigest.substring(colon + 1).toLowerCase(Locale.ROOT) : null;
  }
}
