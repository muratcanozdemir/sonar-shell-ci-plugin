package io.github.shellci.ci.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.shellci.Fixtures;
import io.github.shellci.ci.Platform;
import io.github.shellci.ci.scripts.ScriptExtractor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

class ModelTest {

  private static CiFile fixture(String path, Platform platform) {
    return CiFile.parse(Fixtures.read(path), platform);
  }

  @Test
  void parse_errors_and_empty_documents() {
    assertThatThrownBy(() -> CiFile.parse("a: [", Platform.GITLAB_CI))
      .isInstanceOf(CiFile.ParseException.class)
      .satisfies(e -> assertThat(((CiFile.ParseException) e).line()).isEqualTo(1));
    // unknown escape: rejected by the scanner, not the composer
    assertThatThrownBy(() -> CiFile.parse("a: \"\\q\"", Platform.GITLAB_CI)).isInstanceOf(CiFile.ParseException.class);
    assertThat(CiFile.parse("", Platform.GITHUB_ACTIONS).root()).isNull();
    assertThat(CiFile.parse("---\n...\n---\na: 1\n", Platform.GITHUB_ACTIONS).root()).isNotNull();
  }

  @Test
  void lines_and_spans() {
    CiFile file = CiFile.parse("k: v\r\nemoji: \"😀 x\"\n", Platform.GITHUB_ACTIONS);
    assertThat(file.lines()).containsExactly("k: v", "emoji: \"😀 x\"", "");
    assertThat(file.line(0)).isEmpty();
    assertThat(file.line(99)).isEmpty();
    ScalarNode emoji = (ScalarNode) Yaml.get(file.root(), "emoji").orElseThrow();
    // the emoji is one code point but two Java chars: the span must still end at the closing quote
    Span span = file.span(emoji);
    assertThat(file.line(2).substring(span.column(), span.endColumn())).isEqualTo("\"😀 x\"");
    assertThat(file.find(emoji, java.util.regex.Pattern.compile("x"))).hasSize(1);
  }

  @Test
  void yaml_helpers_tolerate_odd_trees() {
    CiFile file = CiFile.parse("? [complex, key]\n: value\nplain: 1\nlist: [a, {b: c}]\n", Platform.GITLAB_CI);
    NodeTuple complex = Yaml.entries(file.root()).get(0);
    assertThat(Yaml.keyName(complex)).isEmpty();
    assertThat(Yaml.get(file.root(), "complex")).isEmpty();
    assertThat(Yaml.scalar(Yaml.get(file.root(), "list").orElseThrow())).isEmpty();
    assertThat(Yaml.entries(Yaml.get(file.root(), "plain").orElseThrow())).isEmpty();
    assertThat(Yaml.items(file.root())).isEmpty();

    List<String> scalars = new ArrayList<>();
    Yaml.visitScalars(file.root(), s -> scalars.add(s.getValue()));
    assertThat(scalars).containsExactly("complex", "key", "value", "plain", "1", "list", "a", "b", "c");
    List<Node> mappings = new ArrayList<>();
    Yaml.visitMappings(file.root(), mappings::add);
    assertThat(mappings).hasSize(2);
  }

  @Test
  void recursive_aliases_terminate() {
    CiFile file = CiFile.parse("a: &x\n  b: 1\n  c: *x\n", Platform.GITLAB_CI);
    List<String> scalars = new ArrayList<>();
    Yaml.visitScalars(file.root(), s -> scalars.add(s.getValue()));
    assertThat(scalars).containsExactly("a", "b", "1", "c");
    List<Node> mappings = new ArrayList<>();
    Yaml.visitMappings(file.root(), mappings::add);
    assertThat(mappings).hasSize(2);
  }

  @Test
  void github_navigation() {
    CiFile workflow = fixture("github/edge.yml", Platform.GITHUB_ACTIONS);
    assertThat(GitHub.isWorkflow(workflow)).isTrue();
    assertThat(GitHub.isAction(workflow)).isFalse();
    assertThat(GitHub.triggers(workflow)).containsOnlyKeys("workflow_dispatch");
    assertThat(GitHub.jobs(workflow)).extracting(GitHub.Job::id).containsExactly("containers", "job-perms", "reusable");

    CiFile action = fixture("github/action-no-shell.yml", Platform.GITHUB_ACTIONS);
    assertThat(GitHub.isAction(action)).isTrue();
    assertThat(GitHub.scripts(action)).extracting(ScriptNode::dialect).containsExactly(null, null, "bash", "sh");

    // another platform's file is not a GitHub workflow
    CiFile gitlab = fixture("samples/gitlab-ci/.gitlab-ci.yml", Platform.GITLAB_CI);
    assertThat(GitHub.triggers(gitlab)).isEmpty();
    assertThat(GitHub.jobs(gitlab)).isEmpty();
    assertThat(GitHub.isWorkflow(gitlab)).isFalse();
    assertThat(GitHub.isAction(CiFile.parse("", Platform.GITHUB_ACTIONS))).isFalse();
  }

  @Test
  void azure_navigation() {
    CiFile file = fixture("azure/edge.azure-pipelines.yml", Platform.AZURE_PIPELINES);
    List<ScriptNode> scripts = Azure.steps(file).stream().flatMap(s -> Azure.scripts(s).stream()).toList();
    // script, powershell, CmdLine@2, PowerShell@2, AzureCLI@2 (pscore), bash task, CmdLine@2 on Windows
    assertThat(scripts).extracting(ScriptNode::dialect).containsExactly("bash", null, "bash", null, null, "bash", null);
    assertThat(Azure.images(file)).extracting(ScalarNode::getValue).containsExactly("mcr.microsoft.com/powershell");
    assertThat(Azure.steps(fixture("gitlab/edge.yml", Platform.GITLAB_CI))).isEmpty();
    assertThat(Azure.images(fixture("gitlab/edge.yml", Platform.GITLAB_CI))).isEmpty();

    assertThat(Azure.runsOnWindows(null)).isFalse();
    CiFile pools = CiFile.parse("a: {vmImage: windows-2022}\nb: {name: Win-agents}\nc: {vmImage: macos-latest}\n", Platform.AZURE_PIPELINES);
    assertThat(Azure.runsOnWindows(Yaml.get(pools.root(), "a").orElseThrow())).isTrue();
    assertThat(Azure.runsOnWindows(Yaml.get(pools.root(), "b").orElseThrow())).isTrue();
    assertThat(Azure.runsOnWindows(Yaml.get(pools.root(), "c").orElseThrow())).isFalse();
  }

  @Test
  void gitlab_navigation() {
    CiFile file = fixture("gitlab/edge.yml", Platform.GITLAB_CI);
    assertThat(GitLab.jobs(file)).extracting(GitLab.Job::name).containsExactly("job", ".setup", "component-job");
    assertThat(GitLab.jobs(file).get(0).key()).isInstanceOf(ScalarNode.class);
    // default after_script, global before_script, job after_script (the !reference is skipped), .setup script
    List<ScriptNode> scripts = GitLab.scripts(file);
    assertThat(scripts).extracting(ScriptNode::dialect).containsOnly("sh");
    assertThat(scripts).extracting(s -> s.parts().get(0).getValue())
      .containsExactly("echo done", "echo global", "echo after", "echo setup");
    assertThat(GitLab.includes(file)).hasSize(1);

    CiFile github = fixture("github/edge.yml", Platform.GITHUB_ACTIONS);
    assertThat(GitLab.jobs(github)).isEmpty();
    assertThat(GitLab.scripts(github)).isEmpty();
    assertThat(GitLab.images(github)).isEmpty();
    assertThat(GitLab.includes(github)).isEmpty();
  }

  @Test
  void script_extraction_edge_cases() {
    CiFile edge = fixture("github/edge.yml", Platform.GITHUB_ACTIONS);
    List<ScriptExtractor.RunScript> scripts = ScriptExtractor.extractShellScripts(edge);
    // quoted (columns unknown: escapes) and "echo ok"; the folded scalar is skipped
    assertThat(scripts).hasSize(2);
    ScriptExtractor.RunScript quoted = scripts.get(0);
    assertThat(quoted.script()).isEqualTo("echo \"quoted\" && cd /x\n");
    assertThat(quoted.sourceColumn(1, 1)).isEqualTo(-1);
    assertThat(quoted.sourceLine(5)).isEqualTo(19); // out-of-range lines clamp

    assertThat(ScriptExtractor.scriptNodes(CiFile.parse("", Platform.GITLAB_CI))).isEmpty();

    // a job without runs-on runs on Linux: bash
    assertThat(GitHub.scripts(fixture("github/no-on.yml", Platform.GITHUB_ACTIONS))).extracting(ScriptNode::dialect).containsExactly("bash");

    // a plain scalar continued on the next line is folded by YAML: its lines do not map, so it is left out
    CiFile multiLinePlain = CiFile.parse("job:\n  script:\n    - echo one\n      two\n    - echo three\n", Platform.GITLAB_CI);
    assertThat(ScriptExtractor.extractShellScripts(multiLinePlain).get(0).scriptLines()).containsExactly("echo three");

    // a literal block whose lines cannot be matched against the source (tab indentation indicator mismatch)
    CiFile literal = CiFile.parse("job:\n  script:\n    - |\n      one\n\n      two\n", Platform.GITLAB_CI);
    ScriptExtractor.RunScript block = ScriptExtractor.extractShellScripts(literal).get(0);
    assertThat(block.scriptLines()).containsExactly("one", "", "two");
    assertThat(block.sourceColumn(2, 1)).isZero();
    assertThat(block.sourceLine(3)).isEqualTo(6);
  }
}
