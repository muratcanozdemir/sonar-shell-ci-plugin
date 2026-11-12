package io.github.shellci.ci;

import java.util.List;
import java.util.regex.Pattern;

/** The CI systems whose pipeline files are analyzed, with how to recognize their files and template expressions. */
public enum Platform {

  GITHUB_ACTIONS("GitHub Actions", "sonar.ci.githubActions.patterns",
    "**/.github/workflows/*.yml,**/.github/workflows/*.yaml,**/action.yml,**/action.yaml",
    // ${{ expression }}
    List.of(Pattern.compile("\\$\\{\\{.*?}}"))),

  GITLAB_CI("GitLab CI", "sonar.ci.gitlabCi.patterns",
    "**/.gitlab-ci.yml,**/.gitlab-ci.yaml,**/.gitlab/ci/**/*.yml,**/.gitlab/ci/**/*.yaml",
    // $[[ inputs.name ]] (CI/CD component and include inputs). $VARS are real environment variables.
    List.of(Pattern.compile("\\$\\[\\[.*?]]"))),

  AZURE_PIPELINES("Azure Pipelines", "sonar.ci.azurePipelines.patterns",
    "**/azure-pipelines.yml,**/azure-pipelines.yaml,**/*.azure-pipelines.yml,**/*.azure-pipelines.yaml,"
      + "**/.azure-pipelines/**/*.yml,**/.azure-pipelines/**/*.yaml,**/.azuredevops/**/*.yml,**/.azuredevops/**/*.yaml",
    // ${{ template expression }}, $[ runtime expression ], $(Macro.Variable)
    List.of(Pattern.compile("\\$\\{\\{.*?}}"), Pattern.compile("\\$\\[.*?]"), Pattern.compile("\\$\\([A-Za-z_][\\w.\\-]*\\)")));

  private final String displayName;
  private final String patternsProperty;
  private final String defaultPatterns;
  private final List<Pattern> expressions;

  Platform(String displayName, String patternsProperty, String defaultPatterns, List<Pattern> expressions) {
    this.displayName = displayName;
    this.patternsProperty = patternsProperty;
    this.defaultPatterns = defaultPatterns;
    this.expressions = expressions;
  }

  public String displayName() {
    return displayName;
  }

  /** Property listing the path patterns of this platform's files. Never of the form sonar.&lt;language&gt;.file.patterns. */
  public String patternsProperty() {
    return patternsProperty;
  }

  public String defaultPatterns() {
    return defaultPatterns;
  }

  /** Template syntax replaced by the CI system before the script runs. */
  public List<Pattern> expressions() {
    return expressions;
  }
}
