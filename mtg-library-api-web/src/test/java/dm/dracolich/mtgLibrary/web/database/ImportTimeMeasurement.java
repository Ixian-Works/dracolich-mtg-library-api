package dm.dracolich.mtgLibrary.web.database;

import dm.dracolich.mtgLibrary.integration.client.ScryfallClient;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallBulkDataDto;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallCardDto;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallListDto;
import dm.dracolich.mtgLibrary.web.config.MongoIndexes;
import dm.dracolich.mtgLibrary.web.repository.ArtPropertyRepository;
import dm.dracolich.mtgLibrary.web.repository.CardLayoutRepository;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import dm.dracolich.mtgLibrary.web.repository.CollectionSetRepository;
import dm.dracolich.mtgLibrary.web.repository.FrameEffectRepository;
import dm.dracolich.mtgLibrary.web.repository.RulingRepository;
import dm.dracolich.mtgLibrary.web.repository.SubtypeRepository;
import dm.dracolich.mtgLibrary.web.repository.SymbolRepository;
import dm.dracolich.mtgLibrary.web.seed.ScryfallSeeder;
import dm.dracolich.mtgLibrary.web.support.EmbeddedMongo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Times the Scryfall import with and without the indexes, on a synthetic catalogue fed through the
 * seeder's real sync path. Not a test: it runs only when asked, because it takes a while.
 *
 * <pre>mvn test -pl mtg-library-api-web -am -Dtest=ImportTimeMeasurement -Dmeasure.import=true \
 *     -Dmeasure.import.cards=5000 -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@EnabledIfSystemProperty(named = "measure.import", matches = "true")
class ImportTimeMeasurement {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CARDS = Integer.getInteger("measure.import.cards", 5000);

    private static List<ScryfallCardDto> catalogue() {
        return java.util.stream.IntStream.range(0, CARDS)
                .mapToObj(i -> JSON.readValue("""
                        {"id": "s-%1$d", "oracle_id": "o-%1$d", "name": "Card %1$d", "layout": "normal",
                         "type_line": "Creature — Goblin Warrior", "mana_cost": "{1}{R}", "cmc": 2.0,
                         "colors": ["R"], "color_identity": ["R"], "keywords": ["Haste"],
                         "legalities": {"commander": "legal", "modern": "legal"},
                         "set": "tst", "set_id": "set-1", "rarity": "common", "artist": "An Artist",
                         "prices": {"usd": "0.25"}, "released_at": "2020-01-01"}
                        """.formatted(i), ScryfallCardDto.class))
                .toList();
    }

    private static Duration timeSync(boolean withIndexes) {
        try (AnnotationConfigApplicationContext context = EmbeddedMongo.freshContext()) {
            ScryfallClient scryfall = mock(ScryfallClient.class);
            when(scryfall.getSets()).thenReturn(Mono.just(new ScryfallListDto<>("list", false, null, 0, List.of())));
            when(scryfall.getSymbology()).thenReturn(Mono.just(new ScryfallListDto<>("list", false, null, 0, List.of())));
            when(scryfall.getBulkDataList()).thenReturn(Mono.just(new ScryfallListDto<>("list", false, null, 2, List.of(
                    new ScryfallBulkDataDto("b1", "oracle_cards", null, null, "cards", null, null, null, null),
                    new ScryfallBulkDataDto("b2", "rulings", null, null, "rulings", null, null, null, null)))));
            List<ScryfallCardDto> catalogue = catalogue();
            when(scryfall.streamBulkCards(anyString())).thenReturn(Flux.fromIterable(catalogue));
            when(scryfall.streamBulkRulings(anyString())).thenReturn(Flux.empty());

            MongoIndexes indexes = context.getBean(MongoIndexes.class);
            ScryfallSeeder seeder = new ScryfallSeeder(scryfall, context.getBean(ReactiveMongoTemplate.class),
                    context.getBean(CollectionSetRepository.class), context.getBean(SymbolRepository.class),
                    context.getBean(CardRepository.class), context.getBean(ArtPropertyRepository.class),
                    context.getBean(RulingRepository.class), context.getBean(CardLayoutRepository.class),
                    context.getBean(FrameEffectRepository.class), context.getBean(SubtypeRepository.class));

            if (withIndexes) indexes.ensureAll().block(Duration.ofMinutes(1));
            long start = System.nanoTime();
            seeder.sync().block(Duration.ofHours(1));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(context.getBean(CardRepository.class).count().block()).isEqualTo(CARDS);
            return took;
        }
    }

    @Test
    void importWithAndWithoutIndexes() {
        // A first import pays for the database files, the JIT and the downloads; time what follows.
        timeSync(true);
        Duration without = timeSync(false);
        Duration with = timeSync(true);
        System.out.printf("[library#9] import of %d cards: without indexes %d ms, with indexes %d ms%n",
                CARDS, without.toMillis(), with.toMillis());
    }
}
