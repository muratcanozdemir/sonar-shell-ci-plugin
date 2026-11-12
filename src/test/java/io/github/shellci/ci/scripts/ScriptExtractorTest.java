package io.github.shellci.ci.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.shellci.Fixtures;
import io.github.shellci.ci.Platform;
import io.github.shellci.ci.model.CiFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptExtractorTest {

  private static List<ScriptExtractor.RunScript> extract(String fixture) {
    return ScriptExtractor.extractShellScripts(CiFile.parse(Fixtures.read(fixture), Fixtures.platformOf(fixture)));
  }

  @Test
  void extracts_bash_and_sh_scripts_with_source_positions() throws Exception {
    List<ScriptExtractor.RunScript> scripts = extract("samples/github-actions/.github/workflows/scripts.yml");
    // plain, literal block, double-quoted, sh block. Folded, pwsh and the Windows job are skipped.
    assertThat(scripts).extracting(ScriptExtractor.RunScript::dialect).containsExactly("bash", "bash", "bash", "sh");

    ScriptExtractor.RunScript plain = scripts.get(0);
    assertThat(plain.script()).isEqualTo("cd $HOME/build\n");
    assertThat(plain.sourceLine(1)).isEqualTo(10);
    assertThat(plain.sourceColumn(1, 4)).isEqualTo(16); // "$HOME" in "      - run: cd $HOME/build"

    ScriptExtractor.RunScript block = scripts.get(1);
    assertThat(block.script()).isEqualTo("echo \"start\"\n  cd /tmp\necho \"" + "_".repeat("${{ github.sha }}".length()) + "\" > out\nls `pwd`\n");
    assertThat(block.sourceLine(1)).isEqualTo(13);
    assertThat(block.sourceLine(2)).isEqualTo(14);
    assertThat(block.sourceColumn(2, 3)).isEqualTo(12); // "cd" after the extra indentation
    assertThat(block.sourceLine(4)).isEqualTo(16);

    ScriptExtractor.RunScript quoted = scripts.get(2);
    assertThat(quoted.sourceLine(1)).isEqualTo(17);
    assertThat(quoted.sourceColumn(1, 1)).isEqualTo(14);
  }

  @Test
  void composite_actions_need_an_explicit_shell() throws Exception {
    assertThat(extract("github/action.yml")).extracting(ScriptExtractor.RunScript::dialect).containsExactly("bash");
  }

  @Test
  void masks_expressions_keeping_columns() {
    assertThat(ScriptExtractor.mask(List.of("a ${{ x }} b ${{y}}"), Platform.GITHUB_ACTIONS.expressions()))
      .isEqualTo("a ________ b ______\n");
    assertThat(ScriptExtractor.mask(List.of("echo $(Build.SourceBranch) $[ x ] $HOME $(pwd)"), Platform.AZURE_PIPELINES.expressions()))
      .isEqualTo("echo " + "_".repeat("$(Build.SourceBranch)".length()) + " " + "_".repeat("$[ x ]".length()) + " $HOME ______\n");
  }

  @Test
  void parse_errors_carry_a_line() {
    assertThatThrownBy(() -> extract("github/broken.yml"))
      .isInstanceOf(CiFile.ParseException.class)
      .satisfies(e -> assertThat(((CiFile.ParseException) e).line()).isGreaterThanOrEqualTo(4));
  }

  @Test
  void gitlab_scripts_join_before_script_and_script() throws Exception {
    List<ScriptExtractor.RunScript> scripts = extract("samples/gitlab-ci/.gitlab-ci.yml");
    // default before_script, build (before_script + script), build after_script, test:alpine, .deploy-template
    assertThat(scripts).extracting(ScriptExtractor.RunScript::dialect).containsExactly("bash", "bash", "bash", "sh", "bash");
    ScriptExtractor.RunScript build = scripts.get(1);
    assertThat(build.scriptLines()).containsExactly("OUT_DIR=dist", "npm ci", "cd $OUT_DIR", "for f in $(ls *.js); do", "  echo \"$f\"", "done");
    assertThat(build.sourceLine(1)).isEqualTo(39);
    assertThat(build.sourceLine(3)).isEqualTo(42);
    assertThat(build.sourceColumn(3, 1)).isEqualTo(6);
    assertThat(build.sourceLine(4)).isEqualTo(44);
    assertThat(build.sourceColumn(4, 1)).isEqualTo(6);
    assertThat(scripts.get(4).scriptLines().get(0)).isEqualTo("echo \"deploying " + "_".repeat("$[[ inputs.environment ]]".length()) + "\"");
  }

  @Test
  void azure_scripts_follow_the_pool() throws Exception {
    List<ScriptExtractor.RunScript> scripts = extract("samples/azure-pipelines/azure-pipelines.yml");
    // Linux: script, bash, Bash@3, AzureCLI@2 (pwsh skipped). Windows: bash only (script runs in cmd)
    assertThat(scripts).hasSize(5);
    assertThat(scripts.get(1).scriptLines().get(0)).isEqualTo("cd " + "_".repeat("$(Build.SourcesDirectory)".length()));
    assertThat(scripts.get(4).script()).isEqualTo("cd $HOME\n");
    assertThat(scripts.get(4).sourceLine(1)).isEqualTo(52);
  }
}
