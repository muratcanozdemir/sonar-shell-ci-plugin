package io.github.shellci.ci.checks;

import io.github.shellci.ci.model.CiFile;
import io.github.shellci.ci.model.ScriptNode;
import io.github.shellci.ci.model.Span;
import io.github.shellci.ci.scripts.ScriptExtractor;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scripts that download code and execute it without checking it: {@code curl ... | sh}, {@code iwr ... | iex}. */
public class RemoteScriptExecutionCheck implements CiCheck {

  private static final Pattern DOWNLOAD_AND_RUN = Pattern.compile(String.join("|",
    // curl -fsSL https://x/install.sh | sudo -E bash -s -- args
    "\\b(?:curl|wget)\\b[^|;&\\n]*\\|\\s*(?:sudo\\s+(?:-\\S+\\s+)*)?(?:ba|z|k|da)?sh\\b",
    // bash <(curl ...), sh -c "$(curl ...)"
    "\\b(?:ba|z|k|da)?sh\\s+(?:-c\\s+)?[\"']?(?:<\\(|\\$\\()\\s*(?:curl|wget)\\b",
    // PowerShell: iwr https://x | iex ; iex (iwr https://x).Content
    "\\b(?:iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^|\\n]*\\|\\s*(?:iex|invoke-expression)\\b",
    // iex ((New-Object System.Net.WebClient).DownloadString('https://x')) ; Invoke-Expression (iwr https://x)
    "\\b(?:iex|invoke-expression)\\s*\\(+\\s*(?:new-object\\s+(?:system\\.)?net\\.webclient\\s*\\)\\s*\\.\\s*downloadstring"
      + "|(?:iwr|irm|invoke-webrequest|invoke-restmethod)\\b)",
    // (New-Object Net.WebClient).DownloadString('https://x') | iex
    "\\.downloadstring\\s*\\([^)]*\\)\\s*\\|\\s*(?:iex|invoke-expression)\\b"),
    Pattern.CASE_INSENSITIVE);

  @Override
  public void check(CiFile file, Context context) {
    for (ScriptNode node : ScriptExtractor.scriptNodes(file)) {
      ScriptExtractor.toScript(file, node).ifPresent(script -> scan(script, context));
    }
  }

  private static void scan(ScriptExtractor.RunScript script, Context context) {
    List<String> lines = script.scriptLines();
    for (int i = 0; i < lines.size(); i++) {
      Matcher m = DOWNLOAD_AND_RUN.matcher(lines.get(i));
      while (m.find()) {
        int line = script.sourceLine(i + 1);
        int start = script.sourceColumn(i + 1, m.start() + 1);
        int end = script.sourceColumn(i + 1, m.end() + 1);
        Span span = start < 0 ? new Span(line, 0, line, Integer.MAX_VALUE) : new Span(line, start, line, end);
        context.report(span, "Make sure executing this downloaded script without verifying it is safe here.");
      }
    }
  }
}
