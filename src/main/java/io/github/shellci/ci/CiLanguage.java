package io.github.shellci.ci;

import org.sonar.api.resources.AbstractLanguage;

/**
 * Owns the CI pipeline rules and quality profile. It claims no files: pipeline files are usually indexed as YAML by
 * another plugin, and two languages cannot claim the same file. {@link CiSensor} selects pipeline files by path
 * ({@link Platform#patternsProperty()}) whatever their language.
 */
public class CiLanguage extends AbstractLanguage {

  public static final String KEY = "ci";
  public static final String NAME = "CI Pipelines";

  public CiLanguage() {
    super(KEY, NAME);
  }

  @Override
  public String[] getFileSuffixes() {
    return new String[0];
  }
}
