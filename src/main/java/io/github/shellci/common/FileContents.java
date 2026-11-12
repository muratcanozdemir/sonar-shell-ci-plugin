package io.github.shellci.common;

import java.io.IOException;
import java.util.List;
import org.sonar.api.batch.fs.InputFile;

public final class FileContents {

  private FileContents() {
  }

  /** Splits on \n, \r\n and \r the same way the scanner counts lines. */
  public static List<String> lines(InputFile file) throws IOException {
    return lines(file.contents());
  }

  public static List<String> lines(String contents) {
    return List.of(contents.split("\r\n|\r|\n", -1));
  }
}
