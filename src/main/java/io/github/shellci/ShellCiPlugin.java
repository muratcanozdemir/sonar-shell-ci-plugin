package io.github.shellci;

import io.github.shellci.ci.CiLanguage;
import io.github.shellci.ci.CiQualityProfile;
import io.github.shellci.ci.CiRulesDefinition;
import io.github.shellci.ci.CiSensor;
import io.github.shellci.shell.ShellCheckSensor;
import io.github.shellci.shell.ShellLanguage;
import io.github.shellci.shell.ShellQualityProfile;
import io.github.shellci.shell.ShellRulesDefinition;
import org.sonar.api.Plugin;

public class ShellCiPlugin implements Plugin {

  @Override
  public void define(Context context) {
    context.addExtensions(Settings.definitions());
    context.addExtensions(
      ShellLanguage.class,
      ShellRulesDefinition.class,
      ShellQualityProfile.class,
      ShellCheckSensor.class,
      CiLanguage.class,
      CiRulesDefinition.class,
      CiQualityProfile.class,
      CiSensor.class);
  }
}
