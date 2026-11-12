package io.github.shellci.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShellCheckCatalogTest {

  @Test
  void catalog() {
    assertThat(ShellCheckCatalog.contains("SC2086")).isTrue();
    assertThat(ShellCheckCatalog.contains("SC9999")).isFalse();
    assertThat(ShellCheckCatalog.entries()).allSatisfy(e -> assertThat(e.key()).matches("SC\\d{4}"));
    assertThatThrownBy(() -> ShellCheckCatalog.load("/no/such/resource.json")).isInstanceOf(IllegalStateException.class);
  }
}
