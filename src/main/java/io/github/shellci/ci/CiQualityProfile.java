package io.github.shellci.ci;

import io.github.shellci.shell.ShellQualityProfile;
import org.sonar.api.server.profile.BuiltInQualityProfilesDefinition;

public class CiQualityProfile implements BuiltInQualityProfilesDefinition {

  @Override
  public void define(Context context) {
    NewBuiltInQualityProfile profile = context.createBuiltInQualityProfile(ShellQualityProfile.NAME, CiLanguage.KEY);
    profile.setDefault(true);
    for (CiRule rule : CiRule.values()) {
      if (rule.defaultActive()) {
        profile.activateRule(CiRulesDefinition.REPOSITORY, rule.key());
      }
    }
    profile.done();
  }
}
