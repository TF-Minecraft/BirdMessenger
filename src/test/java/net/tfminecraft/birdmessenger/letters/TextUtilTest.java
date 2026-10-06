package net.tfminecraft.birdmessenger.letters;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextUtilTest {
  @Test
  void expandsEveryHexDigitWithoutTreatingTextAsReplacementSyntax() {
    assertEquals(
        "§x§1§2§a§B§e§F$1\\quoted §agreen §x§0§0§0§0§0§0black",
        TextUtil.color("&#12aBeF$1\\quoted &agreen &#000000black"));
    assertEquals("&#ZZ0000 incomplete &#12345", TextUtil.color("&#ZZ0000 incomplete &#12345"));
    assertEquals("Plain", TextUtil.color("Plain"));
    assertEquals("", TextUtil.color(""));
    assertNull(TextUtil.color(null));
  }
}
