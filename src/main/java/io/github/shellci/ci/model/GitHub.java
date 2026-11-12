package io.github.shellci.ci.model;

import io.github.shellci.ci.Platform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** Navigation over GitHub Actions workflows ({@code .github/workflows/*.yml}) and action metadata ({@code action.yml}). */
public final class GitHub {

  public record Job(String id, NodeTuple tuple) {
    public Node key() {
      return tuple.getKeyNode();
    }

    public Node body() {
      return tuple.getValueNode();
    }
  }

  /** A step of a job, or of a composite action ({@code job} is then null). */
  public record Step(Job job, Node body) {
  }

  private GitHub() {
  }

  public static boolean applies(CiFile file) {
    return file.platform() == Platform.GITHUB_ACTIONS && file.root() != null;
  }

  /** A workflow has jobs; an action metadata file has runs. */
  public static boolean isWorkflow(CiFile file) {
    return applies(file) && Yaml.get(file.root(), "jobs").isPresent();
  }

  public static boolean isAction(CiFile file) {
    return applies(file) && Yaml.get(file.root(), "jobs").isEmpty() && Yaml.get(file.root(), "runs").isPresent();
  }

  /** Event names from the {@code on:} key, each mapped to the node to report on. */
  public static Map<String, Node> triggers(CiFile file) {
    Map<String, Node> result = new LinkedHashMap<>();
    if (!applies(file)) {
      return result;
    }
    Optional<NodeTuple> on = Yaml.entry(file.root(), "on");
    if (on.isEmpty()) {
      return result;
    }
    Node value = on.get().getValueNode();
    if (value instanceof ScalarNode s) {
      result.put(s.getValue(), s);
    } else if (value instanceof MappingNode) {
      for (NodeTuple t : Yaml.entries(value)) {
        result.put(Yaml.keyName(t), t.getKeyNode());
      }
    } else {
      for (Node item : Yaml.items(value)) {
        Yaml.scalar(item).ifPresent(name -> result.put(name, item));
      }
    }
    return result;
  }

  public static List<Job> jobs(CiFile file) {
    List<Job> jobs = new ArrayList<>();
    if (applies(file)) {
      Yaml.get(file.root(), "jobs").ifPresent(j -> Yaml.entries(j).forEach(t -> jobs.add(new Job(Yaml.keyName(t), t))));
    }
    return jobs;
  }

  public static List<Step> steps(CiFile file) {
    List<Step> steps = new ArrayList<>();
    if (isAction(file)) {
      Yaml.get(file.root(), "runs").flatMap(r -> Yaml.get(r, "steps"))
        .ifPresent(s -> Yaml.items(s).forEach(item -> steps.add(new Step(null, item))));
    }
    for (Job job : jobs(file)) {
      Yaml.get(job.body(), "steps").ifPresent(s -> Yaml.items(s).forEach(item -> steps.add(new Step(job, item))));
    }
    return steps;
  }

  /** {@code run:} scripts with the shell they run in (bash/sh, or null for pwsh, cmd, python...). */
  public static List<ScriptNode> scripts(CiFile file) {
    List<ScriptNode> scripts = new ArrayList<>();
    for (Step step : steps(file)) {
      Yaml.get(step.body(), "run")
        .filter(ScalarNode.class::isInstance)
        .map(ScalarNode.class::cast)
        .ifPresent(run -> scripts.add(ScriptNode.of(run, dialect(file, step))));
    }
    return scripts;
  }

  static String dialect(CiFile file, Step step) {
    Optional<String> shell = Yaml.scalar(step.body(), "shell");
    if (shell.isEmpty() && step.job() != null) {
      shell = defaultsShell(step.job().body());
    }
    if (shell.isEmpty()) {
      shell = defaultsShell(file.root());
    }
    if (shell.isEmpty()) {
      if (step.job() == null || runsOnWindows(step.job().body())) {
        // composite actions must declare a shell; Windows runners default to pwsh
        return null;
      }
      return "bash";
    }
    String command = shell.get().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    if (command.equals("bash") || command.endsWith("/bash")) {
      return "bash";
    }
    if (command.equals("sh") || command.endsWith("/sh")) {
      return "sh";
    }
    return null;
  }

  private static Optional<String> defaultsShell(Node node) {
    return Yaml.get(node, "defaults").flatMap(d -> Yaml.get(d, "run")).flatMap(r -> Yaml.scalar(r, "shell"));
  }

  private static boolean runsOnWindows(Node job) {
    Optional<Node> runsOn = Yaml.get(job, "runs-on");
    if (runsOn.isEmpty()) {
      return false;
    }
    List<String> labels = new ArrayList<>();
    Yaml.visitScalars(runsOn.get(), s -> labels.add(s.getValue().toLowerCase(Locale.ROOT)));
    return labels.stream().anyMatch(l -> l.contains("windows"));
  }

  /** Name of the action referenced by a step's {@code uses:}, lower-cased and without the ref, e.g. "actions/checkout". */
  public static Optional<String> actionName(Node step) {
    return Yaml.scalar(step, "uses").map(u -> {
      int at = u.indexOf('@');
      return (at < 0 ? u : u.substring(0, at)).trim().toLowerCase(Locale.ROOT);
    });
  }
}
