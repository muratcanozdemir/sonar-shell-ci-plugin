package io.github.shellci.ci.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

/** Null-tolerant navigation helpers over snakeyaml-engine node trees. */
public final class Yaml {

  private Yaml() {
  }

  public static Optional<NodeTuple> entry(Node mapping, String key) {
    if (mapping instanceof MappingNode m) {
      for (NodeTuple tuple : m.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode k && key.equals(k.getValue())) {
          return Optional.of(tuple);
        }
      }
    }
    return Optional.empty();
  }

  public static Optional<Node> get(Node mapping, String key) {
    return entry(mapping, key).map(NodeTuple::getValueNode);
  }

  /** Value of {@code key} when it is a scalar. */
  public static Optional<String> scalar(Node mapping, String key) {
    return get(mapping, key).flatMap(Yaml::scalar);
  }

  public static Optional<String> scalar(Node node) {
    return node instanceof ScalarNode s ? Optional.of(s.getValue()) : Optional.empty();
  }

  public static List<NodeTuple> entries(Node node) {
    return node instanceof MappingNode m ? m.getValue() : List.of();
  }

  public static List<Node> items(Node node) {
    return node instanceof SequenceNode s ? s.getValue() : List.of();
  }

  public static String keyName(NodeTuple tuple) {
    return tuple.getKeyNode() instanceof ScalarNode s ? s.getValue() : "";
  }

  /** Visits every scalar in the tree (keys and values). Aliased nodes are visited once per reference. */
  public static void visitScalars(Node node, Consumer<ScalarNode> visitor) {
    visitScalars(node, visitor, new ArrayList<>());
  }

  private static void visitScalars(Node node, Consumer<ScalarNode> visitor, List<Node> path) {
    if (node == null || path.stream().anyMatch(n -> n == node)) {
      return; // guards against recursive aliases
    }
    path.add(node);
    if (node instanceof ScalarNode s) {
      visitor.accept(s);
    } else if (node instanceof MappingNode m) {
      for (NodeTuple t : m.getValue()) {
        visitScalars(t.getKeyNode(), visitor, path);
        visitScalars(t.getValueNode(), visitor, path);
      }
    } else if (node instanceof SequenceNode s) {
      for (Node item : s.getValue()) {
        visitScalars(item, visitor, path);
      }
    }
    path.remove(path.size() - 1);
  }

  /** Visits every mapping in the tree. */
  public static void visitMappings(Node node, Consumer<MappingNode> visitor) {
    visitMappings(node, visitor, new ArrayList<>());
  }

  private static void visitMappings(Node node, Consumer<MappingNode> visitor, List<Node> path) {
    if (node == null || path.stream().anyMatch(n -> n == node)) {
      return;
    }
    path.add(node);
    if (node instanceof MappingNode m) {
      visitor.accept(m);
      for (NodeTuple t : m.getValue()) {
        visitMappings(t.getValueNode(), visitor, path);
      }
    } else if (node instanceof SequenceNode s) {
      for (Node item : s.getValue()) {
        visitMappings(item, visitor, path);
      }
    }
    path.remove(path.size() - 1);
  }
}
