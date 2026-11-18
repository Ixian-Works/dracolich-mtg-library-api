package dm.dracolich.mtgLibrary.web.database;

import com.mongodb.client.model.Filters;
import dm.dracolich.mtgLibrary.web.config.MongoIndexes;
import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.mapper.ArtPropertyMapper;
import dm.dracolich.mtgLibrary.web.mapper.CardMapper;
import dm.dracolich.mtgLibrary.web.repository.ArtPropertyRepository;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import dm.dracolich.mtgLibrary.web.service.CardServiceImpl;
import dm.dracolich.mtgLibrary.web.support.EmbeddedMongo;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database checks from mtg-library-api#9, against a real MongoDB: which indexes exist, whether an
 * exact-name lookup uses one, and how many queries a search page makes. Each test prints what it
 * found, for the record on the issue.
 */
class DatabaseChecksTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private AnnotationConfigApplicationContext context;
    private ReactiveMongoTemplate template;

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        template = context.getBean(ReactiveMongoTemplate.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private List<String> indexNames(String collection) {
        return template.getCollection(collection)
                .flatMapMany(c -> Flux.from(c.listIndexes()))
                .map(index -> index.getString("name") + (Boolean.TRUE.equals(index.getBoolean("unique")) ? " (unique)" : ""))
                .collectList().block(WAIT);
    }

    private void saveCards(int count) {
        Flux.range(0, count)
                .map(i -> CardEntity.builder()
                        .oracleId("o-" + i).name("Card " + i)
                        .nameKeys(Set.of("card " + i))
                        .firstReleaseDate(LocalDate.of(2000, 1, 1).plusDays(i))
                        .build())
                .concatMap(card -> context.getBean(CardRepository.class).save(card))
                .concatMap(card -> context.getBean(ArtPropertyRepository.class).save(ArtPropertyEntity.builder()
                        .scryfallId("s-" + card.getOracleId()).cardId(card.getId())
                        .prices(Map.of("usd", 1.0)).build()))
                .then().block(WAIT);
    }

    @Test
    void springAloneCreatesNoIndexes_andStartupCreatesThemAll() {
        // Before: what the service had until #16. Writing through the repositories creates the
        // collections, and Spring Data creates none of the @Indexed indexes.
        saveCards(1);
        List<String> cardsBefore = indexNames("cards");
        List<String> artBefore = indexNames("art_properties");
        System.out.println("[library#9] cards indexes before: " + cardsBefore);
        System.out.println("[library#9] art_properties indexes before: " + artBefore);
        assertThat(cardsBefore).containsExactly("_id_");
        assertThat(artBefore).containsExactly("_id_");

        context.getBean(MongoIndexes.class).ensureAll().block(WAIT);

        List<String> cardsAfter = indexNames("cards");
        List<String> artAfter = indexNames("art_properties");
        System.out.println("[library#9] cards indexes after: " + cardsAfter);
        System.out.println("[library#9] art_properties indexes after: " + artAfter);
        assertThat(cardsAfter).contains("oracle_id (unique)", "idx_name_keys", "idx_first_release_date_id");
        assertThat(artAfter).contains("scryfall_id (unique)", "card_id");
    }

    @Test
    void anExactNameLookupUsesItsIndex() {
        context.getBean(MongoIndexes.class).ensureAll().block(WAIT);
        saveCards(500);

        Document explain = template.getCollection("cards")
                .flatMap(c -> Mono.from(c.find(Filters.eq("name_keys", "card 250")).explain()))
                .block(WAIT);
        Document winningPlan = explain.get("queryPlanner", Document.class).get("winningPlan", Document.class);
        System.out.println("[library#9] exact-name winning plan: " + winningPlan.toJson());

        assertThat(winningPlan.toJson()).contains("\"IXSCAN\"").contains("idx_name_keys").doesNotContain("COLLSCAN");
        Document stats = explain.get("executionStats", Document.class);
        if (stats != null) {
            System.out.println("[library#9] documents examined: " + stats.get("totalDocsExamined"));
            assertThat(stats.getInteger("totalDocsExamined")).isEqualTo(1);
        }
    }

    @Test
    void aSearchPageIsThreeQueries() {
        context.getBean(MongoIndexes.class).ensureAll().block(WAIT);
        saveCards(60);
        String db = template.getMongoDatabase().map(d -> d.getName()).block(WAIT);
        template.executeCommand(new Document("profile", 2)).block(WAIT);

        var service = new CardServiceImpl(context.getBean(CardRepository.class), context.getBean(ArtPropertyRepository.class),
                context.getBean(CardMapper.class), context.getBean(ArtPropertyMapper.class));
        var page = service.searchCard(null, null, List.of("first_release_date,desc"), 0, 20).block(WAIT);
        template.executeCommand(new Document("profile", 0)).block(WAIT);

        List<Document> operations = template.getCollection("system.profile")
                .flatMapMany(c -> Flux.from(c.find(Filters.in("ns", db + ".cards", db + ".art_properties"))))
                .collectList().block(WAIT);
        List<String> summary = operations.stream().map(DatabaseChecksTest::describe).toList();
        System.out.println("[library#9] a search page's queries: " + summary);

        assertThat(page.getContent()).hasSize(20);
        // Spring Data's reactive count is countDocuments, which MongoDB runs as an aggregation
        // ending in $group; the page itself is the aggregation with $sort.
        assertThat(summary).containsExactlyInAnyOrder(
                "cards: count (aggregate with $group)", "cards: page (aggregate with $sort)", "art_properties: find");
    }

    private static String describe(Document operation) {
        String collection = operation.getString("ns").substring(operation.getString("ns").indexOf('.') + 1);
        Document command = operation.get("command", Document.class);
        if (command.containsKey("aggregate")) {
            String stages = command.getList("pipeline", Document.class).toString();
            return collection + (stages.contains("$group") ? ": count (aggregate with $group)" : ": page (aggregate with $sort)");
        }
        return collection + ": " + command.keySet().iterator().next();
    }
}
