package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Legality;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.entity.CardFaceEntity;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import dm.dracolich.mtgLibrary.web.seed.CommanderRules;
import dm.dracolich.mtgLibrary.web.support.EmbeddedMongo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The commander filters of card search, against a real MongoDB with cards built as the import builds them. */
class CommanderSearchTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private AnnotationConfigApplicationContext context;
    private CardRepository cards;

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        cards = context.getBean(CardRepository.class);
        save("Krenko, Mob Boss", "Legendary Creature — Goblin Warrior", "Haste");
        save("Grist, the Hunger Tide", "Legendary Planeswalker — Grist", "Grist, the Hunger Tide can be your commander.");
        save("Goblin Guide", "Creature — Goblin Scout", "Haste");
        save("Golos, Tireless Pilgrim", "Legendary Artifact Creature — Golem", "", Legality.BANNED);
        save("Thrasios, Triton Hero", "Legendary Creature — Merfolk Wizard", "Partner (You can have two commanders.)", Legality.LEGAL, "Partner");
        save("Tymna the Weaver", "Legendary Creature — Human Cleric", "Partner (You can have two commanders.)", Legality.LEGAL, "Partner");
        save("Pir, Imaginative Rascal", "Legendary Creature — Human Rogue", "Partner with Toothy, Imaginary Friend (When ...)", Legality.LEGAL, "Partner with", "Partner");
        save("Toothy, Imaginary Friend", "Legendary Creature — Illusion", "Partner with Pir, Imaginative Rascal (When ...)", Legality.LEGAL, "Partner with", "Partner");
        save("Wilson, Refined Grizzly", "Legendary Creature — Bear Warrior", "Choose a Background", Legality.LEGAL, "Choose a background");
        save("Acolyte of Bahamut", "Legendary Enchantment — Background", "Commander creatures you own have ...");
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private void save(String name, String type, String text) {
        save(name, type, text, Legality.LEGAL);
    }

    private void save(String name, String type, String text, Legality legality, String... keywords) {
        CardEntity card = CardEntity.builder()
                .oracleId("o-" + name)
                .name(name)
                .keywords(Set.of(keywords))
                .legalities(Map.of(Format.COMMANDER, legality))
                .defaultFace(CardFaceEntity.builder().name(name).fullType(type).oracleText(text).build())
                .build();
        card.setNameKeys(Set.of(name.toLowerCase(Locale.ROOT)));
        CommanderRules.Result rules = CommanderRules.of(card);
        card.setCanBeCommander(rules.canBeCommander());
        card.setPairings(rules.pairings());
        cards.save(card).block(WAIT);
    }

    private String id(String name) {
        return cards.findAll().filter(c -> c.getName().equals(name)).blockFirst(WAIT).getId();
    }

    private List<String> names(CardSearchRecord search) {
        return cards.findByFilters(null, search, List.of("name"), 0, 50).block(WAIT).getContent().stream()
                .map(CardEntity::getName).toList();
    }

    private static CardSearchRecord canBeCommander() {
        return new CardSearchRecord(null, null, null, null, null, null, null, null, null, null, null, null, null, true, null);
    }

    private static CardSearchRecord beside(String id) {
        return new CardSearchRecord(null, null, null, null, null, null, null, null, null, null, null, null, null, null, id);
    }

    @Test
    void onlyCardsThatCanBeACommander() {
        assertThat(names(canBeCommander())).containsExactly(
                "Grist, the Hunger Tide", "Krenko, Mob Boss", "Pir, Imaginative Rascal", "Thrasios, Triton Hero",
                "Toothy, Imaginary Friend", "Tymna the Weaver", "Wilson, Refined Grizzly");
    }

    @Test
    void besideAPartnerAreTheOtherPartnersAndNotALegendaryCreatureWithoutIt() {
        assertThat(names(beside(id("Thrasios, Triton Hero")))).containsExactly("Tymna the Weaver");
    }

    @Test
    void besidePartnerWithIsOnlyTheNamedCard() {
        assertThat(names(beside(id("Pir, Imaginative Rascal")))).containsExactly("Toothy, Imaginary Friend");
    }

    @Test
    void besideChooseABackgroundAreTheBackgroundsAndBesideABackgroundAreThoseCommanders() {
        assertThat(names(beside(id("Wilson, Refined Grizzly")))).containsExactly("Acolyte of Bahamut");
        assertThat(names(beside(id("Acolyte of Bahamut")))).containsExactly("Wilson, Refined Grizzly");
    }

    @Test
    void besideACommanderWithNoSuchAbilityThereIsNothing() {
        assertThat(names(beside(id("Krenko, Mob Boss")))).isEmpty();
    }

    @Test
    void anUnknownCommanderIs404() {
        Throwable error = catchThrowable(() -> names(beside("no-such-card")));

        assertThat(error).isInstanceOf(ResponseException.class);
        assertThat(((ResponseException) error).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
