package io.github.shellci.shell;

/** One finding as emitted by ShellCheck's json/json1 formats. Lines and columns are 1-based, endColumn exclusive. */
public record ShellCheckComment(String file, int line, int endLine, int column, int endColumn, String level, int code, String message) {

  public String ruleKey() {
    return "SC" + code;
  }
}
