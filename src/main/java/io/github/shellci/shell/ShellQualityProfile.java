package io.github.shellci.shell;

import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;

public class ShellQualityProfile implements BuiltInQualityProfilesDefinition {

  public static final String NAME = "Shell & CI way";

  @Override
  public void define(Context context) {
    NewBuiltInQualityProfile profile = context.createBuiltInQualityProfile(NAME, ShellLanguage.KEY);
    profile.setDefault(true);
    for (ShellCheckCatalog.Entry entry : ShellCheckCatalog.entries()) {
      if (entry.defaultActive()) {
        profile.activateRule(ShellRulesDefinition.REPOSITORY, entry.key());
      }
    }
    profile.done();
  }
}
