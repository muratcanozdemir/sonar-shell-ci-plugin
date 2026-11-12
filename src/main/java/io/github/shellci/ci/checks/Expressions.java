package io.github.shellci.ci.checks;

import java.util.regex.Pattern;

/** Patterns over GitHub Actions {@code ${{ }}} expressions. */
public final class Expressions {

  /** A complete {@code ${{ ... }}} expression on a single line. */
  public static final Pattern EXPRESSION = Pattern.compile("\\$\\{\\{.*?}}");

  /**
   * Contexts whose values are controlled by whoever opens an issue, PR, comment, discussion, commit or branch. See
   * GitHub's "Security hardening for GitHub Actions: understanding the risk of script injections".
   */
  public static final Pattern UNTRUSTED_INPUT = Pattern.compile(String.join("|",
    "\\bgithub\\.head_ref\\b",
    "\\bgithub\\.event\\.(?:issue|pull_request|discussion)\\.(?:title|body)\\b",
    "\\bgithub\\.event\\.(?:comment|review|review_comment)\\.body\\b",
    "\\bgithub\\.event\\.pages\\.[\\w*]+\\.page_name\\b",
    "\\bgithub\\.event\\.commits\\.[\\w*]+\\.(?:message|author\\.(?:email|name))\\b",
    "\\bgithub\\.event\\.head_commit\\.(?:message|author\\.(?:email|name))\\b",
    "\\bgithub\\.event\\.pull_request\\.head\\.(?:ref|label|repo\\.default_branch)\\b",
    "\\bgithub\\.event\\.workflow_run\\.(?:head_branch|display_title|head_commit\\.(?:message|author\\.(?:email|name))|pull_requests\\.[\\w*]+\\.head\\.ref)\\b",
    "\\btojson\\(\\s*github(?:\\.event)?\\s*\\)"),
    Pattern.CASE_INSENSITIVE);

  /**
   * Azure Pipelines predefined variables set from commit messages, branch names and user profiles, all chosen by
   * whoever pushes or opens the pull request.
   */
  public static final Pattern AZURE_UNTRUSTED_VARIABLE = Pattern.compile(
    "(?<![\\w.])(?:Build\\.(?:SourceVersionMessage|SourceBranchName|SourceBranch|RequestedForEmail|RequestedFor)"
      + "|System\\.PullRequest\\.(?:SourceBranch|SourceRepositoryURI))(?![\\w.])",
    Pattern.CASE_INSENSITIVE);

  private Expressions() {
  }
}
