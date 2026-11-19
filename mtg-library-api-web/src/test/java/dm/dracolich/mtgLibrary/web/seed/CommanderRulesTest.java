package dm.dracolich.mtgLibrary.web.seed;

import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Legality;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.entity.CardFaceEntity;
import dm.dracolich.mtgLibrary.web.entity.GameplayPropertyEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommanderRulesTest {

    /** A card as the import builds it: its front face's type line and rules text, its keywords, Commander legality. */
    static CardEntity card(String name, String type, String text, Legality commander, String... keywords) {
        return CardEntity.builder()
                .name(name)
                .keywords(Set.of(keywords))
                .legalities(commander == null ? null : Map.of(Format.COMMANDER, commander))
                .defaultFace(CardFaceEntity.builder().name(name).fullType(type).oracleText(text).build())
                .build();
    }

    private static CommanderRules.Result rules(String type, String text, String... keywords) {
        return CommanderRules.of(card("x", type, text, Legality.LEGAL, keywords));
    }

    @Test
    void aLegendaryCreatureCanBeACommander() {
        var result = rules("Legendary Creature — Goblin Warrior", "{T}: Create a 1/1 red Goblin creature token for each Goblin you control.");
        assertThat(result.canBeCommander()).isTrue();
        assertThat(result.pairings()).isEmpty();
    }

    @Test
    void aPlaneswalkerThatSaysSoCanBeACommander() {
        assertThat(rules("Legendary Planeswalker — Grist", "As long as Grist isn't on the battlefield, it's a 1/1 Insect creature in addition to its other types.\nGrist, the Hunger Tide can be your commander.")
                .canBeCommander()).isTrue();
        assertThat(rules("Legendary Planeswalker — Jace", "+1: Draw a card.").canBeCommander()).isFalse();
    }

    @Test
    void cardsThatAreNotCommanders() {
        assertThat(rules("Creature — Goblin", "Haste").canBeCommander()).isFalse();
        assertThat(rules("Legendary Enchantment", "Whenever you cast a spell, scry 1.").canBeCommander()).isFalse();
    }

    @Test
    void aLegendaryVehicleCanBeACommander_byRule903_3() {
        assertThat(rules("Legendary Artifact — Vehicle", "Crew 2").canBeCommander()).isTrue();
        assertThat(rules("Artifact — Vehicle", "Crew 1").canBeCommander()).isFalse();
    }

    @Test
    void aLegendarySpacecraftCanBeACommanderOnlyWithAPowerAndToughness() {
        assertThat(CommanderRules.of(spacecraft("4", "6")).canBeCommander()).isTrue();
        assertThat(CommanderRules.of(spacecraft(null, null)).canBeCommander()).isFalse();
    }

    private static CardEntity spacecraft(String power, String toughness) {
        CardEntity card = card("Ship", "Legendary Artifact — Spacecraft", "Station (Tap another creature you control: Put charge counters ...)", Legality.LEGAL);
        card.getDefaultFace().setGameplayProperty(GameplayPropertyEntity.builder().power(power).toughness(toughness).build());
        return card;
    }

    @Test
    void aBannedOrNotLegalCardIsNotACommander() {
        assertThat(CommanderRules.of(card("Golos", "Legendary Artifact Creature — Golem", "", Legality.BANNED)).canBeCommander()).isFalse();
        assertThat(CommanderRules.of(card("Un", "Legendary Creature — Clown", "", Legality.NOT_LEGAL)).canBeCommander()).isFalse();
        assertThat(CommanderRules.of(card("Old", "Legendary Creature — Elf", "", null)).canBeCommander()).isTrue();
    }

    @Test
    void plainPartner() {
        assertThat(rules("Legendary Creature — Merfolk Wizard", "Partner (You can have two commanders if both have partner.)", "Partner")
                .pairings()).containsExactly("partner");
    }

    @Test
    void partnerWithANamedCard() {
        assertThat(rules("Legendary Creature — Human Wizard", "Partner with Pir, Imaginative Rascal (When this creature enters, target player may put Pir into their hand from their library, then shuffle.)\nWhenever ...", "Partner with", "Partner")
                .pairings()).containsExactly("partner_with:pir, imaginative rascal");
    }

    @Test
    void aNamedPartnerKind() {
        assertThat(rules("Legendary Creature — Human Survivor", "Partner—Survivors (You can have two commanders if both have this ability.)", "Partner")
                .pairings()).containsExactly("partner_group:survivors");
    }

    @Test
    void friendsForeverChooseABackgroundAndDoctors() {
        assertThat(rules("Legendary Creature — Human", "Friends forever", "Friends forever").pairings()).containsExactly("friends_forever");
        assertThat(rules("Legendary Creature — Dwarf", "Choose a Background", "Choose a background").pairings()).containsExactly("choose_a_background");
        assertThat(rules("Legendary Creature — Human", "Doctor's companion", "Doctor's companion").pairings()).containsExactly("doctors_companion");
        assertThat(rules("Legendary Creature — Time Lord Doctor", "Whenever ...").pairings()).containsExactly("doctor");
    }

    @Test
    void aBackgroundIsChosenBesideACommanderButIsNotOne() {
        var result = rules("Legendary Enchantment — Background", "Commander creatures you own have \"...\"");
        assertThat(result.canBeCommander()).isFalse();
        assertThat(result.pairings()).containsExactly("background");
    }
}
