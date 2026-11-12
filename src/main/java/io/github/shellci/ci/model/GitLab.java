package io.github.shellci.ci.model;

import io.github.shellci.ci.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/** Navigation over GitLab CI configuration ({@code .gitlab-ci.yml} and included files). */
public final class GitLab {

  /** Top-level keywords that are not job names. */
  private static final Set<String> GLOBAL_KEYWORDS = Set.of(
    "default", "include", "stages", "variables", "workflow", "image", "services", "before_script", "after_script",
    "cache", "spec");

  public record Job(String name, NodeTuple tuple) {
    public Node key() {
      return tuple.getKeyNode();
    }

    public Node body() {
      return tuple.getValueNode();
    }
  }

  private GitLab() {
  }

  public static boolean applies(CiFile file) {
    return file.platform() == Platform.GITLAB_CI && file.root() != null;
  }

  /** Jobs, including hidden ({@code .name}) template jobs. */
  public static List<Job> jobs(CiFile file) {
    List<Job> jobs = new ArrayList<>();
    if (!applies(file)) {
      return jobs;
    }
    for (NodeTuple tuple : Yaml.entries(file.root())) {
      String name = Yaml.keyName(tuple);
      if (!GLOBAL_KEYWORDS.contains(name) && !Yaml.entries(tuple.getValueNode()).isEmpty()) {
        jobs.add(new Job(name, tuple));
      }
    }
    return jobs;
  }

  /**
   * Scripts in execution units: each job's {@code before_script} + {@code script} (one shell session), each job's
   * {@code after_script}, and the {@code default:}/global {@code before_script} and {@code after_script}.
   */
  public static List<ScriptNode> scripts(CiFile file) {
    List<ScriptNode> scripts = new ArrayList<>();
    if (!applies(file)) {
      return scripts;
    }
    Node root = file.root();
    Optional<Node> defaults = Yaml.get(root, "default");
    String defaultDialect = dialect(defaults.flatMap(d -> Yaml.get(d, "image")).or(() -> Yaml.get(root, "image")).orElse(null), "bash");
    for (Node scope : defaults.map(d -> List.of(d, root)).orElse(List.of(root))) {
      for (String key : List.of("before_script", "after_script")) {
        add(scripts, parts(Yaml.get(scope, key).orElse(null)), defaultDialect);
      }
    }
    for (Job job : jobs(file)) {
      String dialect = dialect(Yaml.get(job.body(), "image").orElse(null), defaultDialect);
      List<ScalarNode> main = new ArrayList<>(parts(Yaml.get(job.body(), "before_script").orElse(null)));
      main.addAll(parts(Yaml.get(job.body(), "script").orElse(null)));
      add(scripts, main, dialect);
      add(scripts, parts(Yaml.get(job.body(), "after_script").orElse(null)), dialect);
    }
    return scripts;
  }

  private static void add(List<ScriptNode> scripts, List<ScalarNode> parts, String dialect) {
    if (!parts.isEmpty()) {
      scripts.add(new ScriptNode(List.copyOf(parts), dialect));
    }
  }

  /** Script lines: a string, or a (possibly nested) list of strings. {@code !reference} tags are not resolved. */
  private static List<ScalarNode> parts(Node node) {
    List<ScalarNode> parts = new ArrayList<>();
    if (node instanceof ScalarNode s && !s.getTag().getValue().equals("!reference")) {
      parts.add(s);
    } else if (node instanceof SequenceNode seq && !seq.getTag().getValue().equals("!reference")) {
      seq.getValue().forEach(item -> parts.addAll(parts(item)));
    }
    return parts;
  }

  /** Alpine and BusyBox images have no bash: scripts run in POSIX sh. */
  private static String dialect(Node image, String fallback) {
    Optional<String> name = image == null ? Optional.empty() : imageName(image).map(s -> s.getValue().toLowerCase(Locale.ROOT));
    if (name.isEmpty()) {
      return fallback;
    }
    return name.get().contains("alpine") || name.get().contains("busybox") ? "sh" : "bash";
  }

  /** The image name of an {@code image:} or {@code services:} entry: a string or a mapping with {@code name:}. */
  public static Optional<ScalarNode> imageName(Node node) {
    if (node instanceof ScalarNode s) {
      return Optional.of(s);
    }
    return Yaml.get(node, "name").filter(ScalarNode.class::isInstance).map(ScalarNode.class::cast);
  }

  /** {@code image:} and {@code services:} references at global, default and job level. */
  public static List<ScalarNode> images(CiFile file) {
    List<ScalarNode> images = new ArrayList<>();
    if (!applies(file)) {
      return images;
    }
    List<Node> scopes = new ArrayList<>();
    scopes.add(file.root());
    Yaml.get(file.root(), "default").ifPresent(scopes::add);
    jobs(file).forEach(j -> scopes.add(j.body()));
    for (Node scope : scopes) {
      Yaml.get(scope, "image").flatMap(GitLab::imageName).ifPresent(images::add);
      Yaml.get(scope, "services").ifPresent(s -> Yaml.items(s).forEach(item -> imageName(item).ifPresent(images::add)));
    }
    return images;
  }

  /** Entries of {@code include:}: each is a string or a mapping (local, remote, project/ref/file, template, component). */
  public static List<Node> includes(CiFile file) {
    List<Node> includes = new ArrayList<>();
    if (!applies(file)) {
      return includes;
    }
    Yaml.get(file.root(), "include").ifPresent(include -> {
      if (include instanceof SequenceNode seq) {
        includes.addAll(seq.getValue());
      } else {
        includes.add(include);
      }
    });
    return includes;
  }
}
