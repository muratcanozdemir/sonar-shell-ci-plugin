package io.github.shellci.ci.model;

import io.github.shellci.ci.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/** Navigation over Azure Pipelines YAML (pipelines and templates). */
public final class Azure {

  /** A step with the pool it runs on (nearest {@code pool:} of its job, stage or pipeline; may be null). */
  public record Step(Node body, Node pool) {
  }

  private Azure() {
  }

  public static boolean applies(CiFile file) {
    return file.platform() == Platform.AZURE_PIPELINES && file.root() != null;
  }

  /** Every step, wherever it appears: pipeline, stage, job, deployment strategy hooks, templates. */
  public static List<Step> steps(CiFile file) {
    List<Step> steps = new ArrayList<>();
    if (applies(file)) {
      collectSteps(file.root(), null, steps);
    }
    return steps;
  }

  private static void collectSteps(Node node, Node pool, List<Step> steps) {
    if (node instanceof MappingNode mapping) {
      Node scopePool = Yaml.get(mapping, "pool").orElse(pool);
      for (NodeTuple tuple : mapping.getValue()) {
        String key = Yaml.keyName(tuple);
        if (key.equals("steps") && tuple.getValueNode() instanceof SequenceNode sequence) {
          for (Node item : sequence.getValue()) {
            steps.add(new Step(item, scopePool));
          }
        } else if (!key.equals("pool")) {
          collectSteps(tuple.getValueNode(), scopePool, steps);
        }
      }
    } else if (node instanceof SequenceNode sequence) {
      sequence.getValue().forEach(item -> collectSteps(item, pool, steps));
    }
  }

  /** The script(s) of a step, with the shell they run in. Covers script, bash, pwsh, powershell and common script tasks. */
  public static List<ScriptNode> scripts(Step step) {
    List<ScriptNode> scripts = new ArrayList<>();
    Node body = step.body();
    scalar(body, "script").ifPresent(s -> scripts.add(ScriptNode.of(s, runsOnWindows(step.pool()) ? null : "bash")));
    scalar(body, "bash").ifPresent(s -> scripts.add(ScriptNode.of(s, "bash")));
    scalar(body, "pwsh").ifPresent(s -> scripts.add(ScriptNode.of(s, null)));
    scalar(body, "powershell").ifPresent(s -> scripts.add(ScriptNode.of(s, null)));

    Optional<String> task = Yaml.scalar(body, "task").map(t -> t.trim().toLowerCase(Locale.ROOT));
    Optional<Node> inputs = Yaml.get(body, "inputs");
    if (task.isPresent() && inputs.isPresent()) {
      String name = task.get().contains("@") ? task.get().substring(0, task.get().indexOf('@')) : task.get();
      switch (name) {
        case "bash" -> scalar(inputs.get(), "script").ifPresent(s -> scripts.add(ScriptNode.of(s, "bash")));
        case "cmdline" -> scalar(inputs.get(), "script")
          .ifPresent(s -> scripts.add(ScriptNode.of(s, runsOnWindows(step.pool()) ? null : "bash")));
        case "powershell" -> scalar(inputs.get(), "script").ifPresent(s -> scripts.add(ScriptNode.of(s, null)));
        case "azurecli" -> {
          String type = Yaml.scalar(inputs.get(), "scriptType").map(t -> t.trim().toLowerCase(Locale.ROOT)).orElse("");
          scalar(inputs.get(), "inlineScript").ifPresent(s -> scripts.add(ScriptNode.of(s, type.equals("bash") ? "bash" : null)));
        }
        default -> {
          // other tasks do not take inline scripts
        }
      }
    }
    return scripts;
  }

  /** Container image references: {@code resources.containers[].image} and job/step {@code container:} images. */
  public static List<ScalarNode> images(CiFile file) {
    List<ScalarNode> images = new ArrayList<>();
    if (!applies(file)) {
      return images;
    }
    Yaml.get(file.root(), "resources").flatMap(r -> Yaml.get(r, "containers"))
      .ifPresent(c -> Yaml.items(c).forEach(item -> scalar(item, "image").ifPresent(images::add)));
    Yaml.visitMappings(file.root(), mapping -> Yaml.get(mapping, "container").ifPresent(container -> {
      if (container instanceof ScalarNode s && looksLikeImage(s.getValue())) {
        images.add(s); // otherwise an alias of resources.containers
      } else {
        scalar(container, "image").ifPresent(images::add);
      }
    }));
    return images;
  }

  private static boolean looksLikeImage(String value) {
    return value.contains(":") || value.contains("/") || value.contains("@");
  }

  static boolean runsOnWindows(Node pool) {
    if (pool == null) {
      return false; // Microsoft-hosted default pool is ubuntu-latest
    }
    List<String> values = new ArrayList<>();
    Yaml.visitScalars(pool, s -> values.add(s.getValue().toLowerCase(Locale.ROOT)));
    return values.stream().anyMatch(v -> v.contains("windows") || v.startsWith("vs20") || v.startsWith("win"));
  }

  private static Optional<ScalarNode> scalar(Node mapping, String key) {
    return Yaml.get(mapping, key).filter(ScalarNode.class::isInstance).map(ScalarNode.class::cast);
  }
}
