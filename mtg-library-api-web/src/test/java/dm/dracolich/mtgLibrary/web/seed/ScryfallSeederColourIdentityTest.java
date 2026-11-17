package dm.dracolich.mtgLibrary.web.seed;

import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallCardDto;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a card's colour identity ends up when it is imported. Scryfall sends it once per card,
 * beside the faces and not inside them.
 */
class ScryfallSeederColourIdentityTest {

    private final JsonMapper json = JsonMapper.builder().build();
    // mapCard touches none of the seeder's collaborators.
    private final ScryfallSeeder seeder = new ScryfallSeeder(null, null, null, null, null, null, null, null, null, null);

    private CardEntity imported(String scryfallCard) {
        return seeder.mapCard(json.readValue(scryfallCard, ScryfallCardDto.class));
    }

    @Test
    void aTwoFacedCardKeepsItsIdentityOnTheDefaultFace() {
        CardEntity card = imported("""
                {"oracle_id": "o-esika", "name": "Esika, God of the Tree // The Prismatic Bridge",
                 "layout": "modal_dfc", "type_line": "Legendary Creature — God // Legendary Enchantment",
                 "color_identity": ["B", "G", "R", "U", "W"],
                 "card_faces": [
                   {"name": "Esika, God of the Tree", "mana_cost": "{1}{G}{G}",
                    "type_line": "Legendary Creature — God", "colors": ["G"]},
                   {"name": "The Prismatic Bridge", "mana_cost": "{W}{U}{B}{R}{G}",
                    "type_line": "Legendary Enchantment", "colors": ["B", "G", "R", "U", "W"]}]}
                """);

        assertThat(card.getDefaultFace().getGameplayProperty().getColorIdentity())
                .containsExactlyInAnyOrder(Color.W, Color.U, Color.B, Color.R, Color.G);
        // The faces keep their own colours: the front of this card is green alone.
        assertThat(card.getDefaultFace().getGameplayProperty().getColors()).containsExactly(Color.G);
        assertThat(card.getFlippedFace().getGameplayProperty().getColorIdentity()).isNull();
    }

    @Test
    void aColourlessTwoFacedCardHasAnEmptyIdentityAndNotAMissingOne() {
        CardEntity card = imported("""
                {"oracle_id": "o-colourless", "name": "Front // Back", "layout": "transform",
                 "type_line": "Artifact // Artifact", "color_identity": [],
                 "card_faces": [
                   {"name": "Front", "type_line": "Artifact", "colors": []},
                   {"name": "Back", "type_line": "Artifact", "colors": []}]}
                """);

        assertThat(card.getDefaultFace().getGameplayProperty().getColorIdentity()).isNotNull().isEmpty();
    }

    @Test
    void aCardWithOneFaceKeepsItsIdentityWhereItAlwaysWas() {
        CardEntity card = imported("""
                {"oracle_id": "o-bolt", "name": "Lightning Bolt", "layout": "normal", "type_line": "Instant",
                 "mana_cost": "{R}", "cmc": 1.0, "colors": ["R"], "color_identity": ["R"]}
                """);

        assertThat(card.getDefaultFace().getGameplayProperty().getColorIdentity()).containsExactly(Color.R);
        assertThat(card.getFlippedFace()).isNull();
    }

    @Test
    void theImportedPrintingsRarityAndSetAreCopiedOntoTheCard() {
        CardEntity card = imported("""
                {"oracle_id": "o-bolt", "name": "Lightning Bolt", "layout": "normal",
                 "type_line": "Instant", "color_identity": ["R"], "colors": ["R"],
                 "rarity": "common", "set": "CLB"}
                """);

        assertThat(card.getRarity()).isEqualTo(dm.dracolich.mtgLibrary.dto.enums.Rarity.COMMON);
        assertThat(card.getSetCode()).isEqualTo("clb");
    }
}
