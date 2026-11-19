package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.web.config.MongoIndexes;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.entity.CollectionSetEntity;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import dm.dracolich.mtgLibrary.web.repository.CollectionSetRepository;
import dm.dracolich.mtgLibrary.web.support.EmbeddedMongo;
import dm.dracolich.forge.exception.ResponseException;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Sets newest first and a set's cards in collector-number order, against a real MongoDB, each from an index. */
class SetAndCardOrderTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private AnnotationConfigApplicationContext context;
    private ReactiveMongoTemplate template;
    private CollectionSetRepository sets;
    private CardRepository cards;

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        template = context.getBean(ReactiveMongoTemplate.class);
        sets = context.getBean(CollectionSetRepository.class);
        cards = context.getBean(CardRepository.class);
        context.getBean(MongoIndexes.class).ensureAll().block(WAIT);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private void set(String code, String name, LocalDate released) {
        sets.save(CollectionSetEntity.builder().scryfallId("s-" + code).code(code).name(name).releaseDate(released).build())
                .block(WAIT);
    }

    private void card(String setCode, String collectorNumber) {
        cards.save(CardEntity.builder()
                        .oracleId(setCode + "-" + collectorNumber)
                        .name("Card " + setCode + " " + collectorNumber)
                        .setCode(setCode)
                        .collectorNumber(collectorNumber)
                        .collectorOrder(CollectorNumbers.order(collectorNumber))
                        .build())
                .block(WAIT);
    }

    @Test
    void setsComeBackNewestFirstByDefaultWithAnUndatedSetLast() {
        set("lea", "Limited Edition Alpha", LocalDate.of(1993, 8, 5));
        set("mh3", "Modern Horizons 3", LocalDate.of(2024, 6, 14));
        set("tbd", "Undated", null);
        set("dsk", "Duskmourn", LocalDate.of(2024, 9, 27));

        var page = sets.findByFilters(null, null, null, 0, 10).block(WAIT);

        assertThat(page.getContent()).extracting(CollectionSetEntity::getCode).containsExactly("dsk", "mh3", "lea", "tbd");
    }

    @Test
    void setsCanComeBackByNameOrOldestFirst() {
        set("lea", "Limited Edition Alpha", LocalDate.of(1993, 8, 5));
        set("mh3", "Modern Horizons 3", LocalDate.of(2024, 6, 14));
        set("dsk", "Duskmourn", LocalDate.of(2024, 9, 27));

        assertThat(sets.findByFilters(null, null, List.of("name"), 0, 10).block(WAIT).getContent())
                .extracting(CollectionSetEntity::getCode).containsExactly("dsk", "lea", "mh3");
        assertThat(sets.findByFilters(null, null, List.of("release_date,asc"), 0, 10).block(WAIT).getContent())
                .extracting(CollectionSetEntity::getCode).containsExactly("lea", "mh3", "dsk");
    }

    @Test
    void anUnknownSetSortIsA400() {
        Throwable error = catchThrowable(() -> sets.findByFilters(null, null, List.of("card_count"), 0, 10).block(WAIT));

        assertThat(error).isInstanceOf(ResponseException.class);
        assertThat(((ResponseException) error).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ResponseException) error).getErrors()).extracting("error").containsExactly("DMD045");
    }

    @Test
    void aSetsCardsComeBackInCollectorNumberOrder() {
        for (String number : List.of("13", "12a", "★12", "2", "12", "A-12", "100")) card("mh3", number);
        card("dsk", "1");
        card("mh3", null);

        var page = cards.findByFilters(null, bySet("mh3"), List.of("collector_number"), 0, 20).block(WAIT);

        assertThat(page.getContent()).extracting(CardEntity::getCollectorNumber)
                .containsExactly("2", "12", "12a", "A-12", "★12", "13", "100", null);
    }

    @Test
    void theDefaultSetOrderUsesItsIndex() {
        for (int i = 0; i < 50; i++) set("s" + i, "Set " + i, LocalDate.of(2000, 1, 1).plusDays(i));

        Query query = new Query(new Criteria()).with(SetSearchOrder.sort(null));
        assertUsesIndex(explainFind("collection_sets", query), "idx_release_date_id");

        Query byName = new Query(new Criteria()).with(SetSearchOrder.sort(List.of("name")));
        assertUsesIndex(explainFind("collection_sets", byName), "idx_set_name_id");
    }

    @Test
    void aSetsCardsInCollectorOrderUseTheirIndex() {
        for (int i = 1; i <= 50; i++) card("mh3", String.valueOf(i));
        for (int i = 1; i <= 50; i++) card("dsk", String.valueOf(i));

        Document match = new Document("set_code", "mh3");
        List<Document> pipeline = CardSearchOrder.pipeline(match, null, List.of("collector_number"), 0, 20);
        Document explain = template.getCollection("cards")
                .flatMap(c -> Mono.from(c.aggregate(pipeline).explain()))
                .block(WAIT);

        assertUsesIndex(explain, "idx_set_collector_number");
    }

    private Document explainFind(String collection, Query query) {
        return template.getCollection(collection)
                .flatMap(c -> Mono.from(c.find(query.getQueryObject()).sort(query.getSortObject()).explain()))
                .block(WAIT);
    }

    private static void assertUsesIndex(Document explain, String index) {
        String plan = explain.toJson();
        System.out.println("[library#24] " + index + " plan: " + plan.substring(0, Math.min(plan.length(), 600)));
        assertThat(plan).contains("\"IXSCAN\"").contains(index).doesNotContain("\"SORT\"").doesNotContain("COLLSCAN");
    }

    private static CardSearchRecord bySet(String setCode) {
        return new CardSearchRecord(null, null, null, null, null, null, null, null, null, null, setCode);
    }
}
