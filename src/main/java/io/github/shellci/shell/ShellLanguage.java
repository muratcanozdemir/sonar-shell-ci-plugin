package io.github.shellci.shell;

import io.github.shellci.Settings;
import java.util.Arrays;
import org.sonar.api.config.Configuration;
import org.sonar.api.resources.AbstractLanguage;

public class ShellLanguage extends AbstractLanguage {

  public static final String KEY = "shell";
  public static final String NAME = "Shell";

  private final Configuration configuration;

  public ShellLanguage(Configuration configuration) {
    super(KEY, NAME);
    this.configuration = configuration;
  }

  @Override
  public String[] getFileSuffixes() {
    String[] suffixes = Arrays.stream(configuration.getStringArray(Settings.SHELL_SUFFIXES))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .toArray(String[]::new);
    return suffixes.length == 0 ? Settings.SHELL_SUFFIXES_DEFAULT.split(",") : suffixes;
  }
}
