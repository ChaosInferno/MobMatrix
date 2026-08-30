package org.aincraft.mobmatrix.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.MobMatrixPlugin;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.junit.jupiter.api.Test;

final class CommandValidationTest {
  @Test
  void resolvesNoAndExLabelsWithoutUsingDisplayText() throws IOException {
    CardCatalog catalog = currentCatalog();
    assertEquals(
        "Dodo", MobMatrixCommand.lookupPublicNumber(catalog, "No. 1").orElseThrow().name());
    assertEquals(
        "Clive Rosfield", MobMatrixCommand.lookupCard(catalog, "Ex. 15").orElseThrow().name());
    assertEquals("Dodo", MobMatrixCommand.lookupPublicNumber(catalog, "1").orElseThrow().name());
    assertTrue(MobMatrixCommand.lookupPublicNumber(catalog, "No. 999").isEmpty());
    assertTrue(MobMatrixCommand.lookupPublicNumber(catalog, "Ex. 0").isEmpty());
  }

  @Test
  void rejectsConsoleMissingTargetAndSelfChallenge() {
    MobMatrixCommand.ChallengeValidation console =
        MobMatrixCommand.validateChallenge(
            null, UUID.randomUUID(), true, false, false, false, false);
    assertFalse(console.accepted());
    MobMatrixCommand.ChallengeValidation missing =
        MobMatrixCommand.validateChallenge(
            UUID.randomUUID(), null, false, false, false, false, false);
    assertFalse(missing.accepted());
    UUID player = UUID.randomUUID();
    MobMatrixCommand.ChallengeValidation self =
        MobMatrixCommand.validateChallenge(player, player, true, false, false, false, false);
    assertFalse(self.accepted());
  }

  @Test
  void enforcesExactFiveDistinctPhysicalCopiesAndBoundedPositiveAmount() {
    List<CardCopyId> five =
        List.of(
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()));
    assertTrue(MobMatrixCommand.validateDeck(five).accepted());
    assertFalse(MobMatrixCommand.validateDeck(five.subList(0, 4)).accepted());
    assertFalse(
        MobMatrixCommand.validateDeck(
                List.of(five.get(0), five.get(0), five.get(1), five.get(2), five.get(3)))
            .accepted());
    assertFalse(MobMatrixCommand.validateAmount(0, 64).accepted());
    assertFalse(MobMatrixCommand.validateAmount(65, 64).accepted());
    assertTrue(MobMatrixCommand.validateAmount(5, 64).accepted());
  }

  @Test
  void challengeValidationCoversPendingAndBusyTransitions() {
    UUID sender = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    assertTrue(
        MobMatrixCommand.validateChallenge(sender, target, true, false, false, false, false)
            .accepted());
    assertFalse(
        MobMatrixCommand.validateChallenge(sender, target, true, false, false, true, false)
            .accepted());
    assertFalse(
        MobMatrixCommand.validateChallenge(sender, target, true, false, false, false, true)
            .accepted());
    assertFalse(
        MobMatrixCommand.validateChallenge(sender, target, true, true, false, false, false)
            .accepted());
    assertFalse(
        MobMatrixCommand.validateChallenge(sender, target, true, false, true, false, false)
            .accepted());
  }

  @Test
  void descriptorDeclaresRequiredPaperAndMapGuiContract() throws IOException {
    String descriptor;
    try (InputStream input = getClass().getResourceAsStream("/paper-plugin.yml")) {
      descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(descriptor.contains("main: org.aincraft.mobmatrix.MobMatrixPlugin"));
    assertTrue(descriptor.contains("api-version: '26.2'"));
    assertTrue(descriptor.contains("MapGUI:"));
    assertTrue(descriptor.contains("load: BEFORE"));
    assertTrue(descriptor.contains("required: true"));
    assertTrue(descriptor.contains("join-classpath: true"));
    assertFalse(descriptor.contains("${version}"));
  }

  private static CardCatalog currentCatalog() throws IOException {
    try (InputStream input =
        CommandValidationTest.class.getResourceAsStream("/catalog/cards.csv")) {
      return new CardCatalog(MobMatrixPlugin.parseCatalog(input.readAllBytes()), "test-catalog");
    }
  }
}
