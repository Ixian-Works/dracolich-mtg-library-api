package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.mtgLibrary.dto.enums.Rarity;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import org.bson.Document;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order and filters a card search sends to MongoDB. Nothing here runs a query: these tests fix the
 * documents that are sent, and the comments say what MongoDB does with them.
 */
class CardSearchOrderTest {

    @Nested
    class Sort {

        @Test
        void theNewestCardsViewSortsByReleaseDateThenId() {
            assertThat(CardSearchOrder.sort(List.of("first_release_date,desc"), null))
                    .isEqualTo(new Document("first_release_date", -1).append("_id", 1));
        }

        @Test
        void everyAllowedKeyMapsToItsStoredField() {
            assertThat(CardSearchOrder.sort(List.of("name", "mana_value,asc", "edhrec_rank,desc"), null))
                    .isEqualTo(new Document("name", 1)
                            .append("default_face.gameplay_property.mana_value", 1)
                            .append(CardSearchOrder.EDHREC, -1)
                            .append("_id", 1));
        }

        @Test
        void keysAndDirectionsIgnoreCaseAndSpaces() {
            assertThat(CardSearchOrder.sort(List.of(" Name , DESC "), null))
                    .isEqualTo(new Document("name", -1).append("_id", 1));
        }

        @Test
        void withoutASortOrANameTheOrderIsStillTotal() {
            assertThat(CardSearchOrder.sort(null, null)).isEqualTo(new Document("_id", 1));
            assertThat(CardSearchOrder.sort(List.of(), "  ")).isEqualTo(new Document("_id", 1));
        }

        @Test
        void aNameWithoutASortRanksTheBestMatchesFirstThenTheMostPlayed() {
            assertThat(CardSearchOrder.sort(null, "bolt")).isEqualTo(new Document(CardSearchOrder.RELEVANCE, 1)
                    .append(CardSearchOrder.EDHREC, 1).append("name", 1).append("_id", 1));
        }

        @Test
        void anExplicitSortWinsOverRelevance() {
            assertThat(CardSearchOrder.sort(List.of("name"), "bolt"))
                    .isEqualTo(new Document("name", 1).append("_id", 1));
        }

        @Test
        void anUnknownKeyOrDirectionIs400() {
            for (String bad : List.of("price", "name,up", "name,asc,extra", "")) {
                assertThatThrownBy(() -> CardSearchOrder.sort(List.of(bad), null))
                        .isInstanceOfSatisfying(ResponseException.class, e -> {
                            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(e.getErrors()).extracting("error").containsExactly("DMD045");
                        });
            }
        }
    }

    @Nested
    class Relevance {

        @Test
        void unrankedCardsSortAfterRankedOnes() {
            assertThat(CardSearchOrder.addFields(null)).isEqualTo(new Document(CardSearchOrder.EDHREC,
                    new Document("$ifNull", List.of("$edhrec_rank", Integer.MAX_VALUE))));
        }

        @Test
        void aNameIsRankedByHowItMatches_andTheTextIsNeverAPattern() {
            Document relevance = (Document) CardSearchOrder.addFields("  Bolt ").get(CardSearchOrder.RELEVANCE);
            Document lowerName = new Document("$toLower", "$name");
            Document words = new Document("$split", List.of(lowerName, " "));

            List<Document> branches = relevance.get("$switch", Document.class).getList("branches", Document.class);
            // The typed text is trimmed and lower-cased, and compared with $eq, $in and $indexOfCP:
            // no $regex, so a bracket or a plus in it is just a character.
            assertThat(branches).containsExactly(
                    new Document("case", new Document("$eq", List.of(lowerName, "bolt"))).append("then", 0),
                    // "Lightning Bolt" and "Bolt of Keranos" both have the whole word, so EDHREC decides.
                    new Document("case", new Document("$in", List.of("bolt", words))).append("then", 1),
                    new Document("case", new Document("$eq", List.of(
                            new Document("$indexOfCP", List.of(lowerName, "bolt")), 0))).append("then", 2),
                    new Document("case", new Document("$anyElementTrue", List.of(new Document("$map",
                            new Document("input", words).append("as", "word").append("in",
                                    new Document("$eq", List.of(new Document("$indexOfCP", List.of("$$word", "bolt")), 0)))))))
                            .append("then", 3));
            assertThat(relevance.get("$switch", Document.class).get("default")).isEqualTo(4);
            assertThat(relevance.toJson()).doesNotContain("$regex");
        }
    }

    @Nested
    class Pipeline {

        @Test
        void aPageIsMatchRankSortSkipLimitAndTheHelperFieldsAreDropped() {
            Document match = new Document("name", "x");
            List<Document> stages = CardSearchOrder.pipeline(match, "bolt", null, 2, 20);

            assertThat(stages).extracting(stage -> stage.keySet().iterator().next())
                    .containsExactly("$match", "$addFields", "$sort", "$skip", "$limit", "$project");
            assertThat(stages.get(0).get("$match")).isSameAs(match);
            assertThat(stages.get(3).get("$skip")).isEqualTo(40L);
            assertThat(stages.get(4).get("$limit")).isEqualTo(20);
            assertThat(stages.get(5).get("$project")).isEqualTo(
                    new Document(CardSearchOrder.EDHREC, 0).append(CardSearchOrder.RELEVANCE, 0));
        }

        @Test
        void aSortOnStoredFieldsAddsNothing_soAnIndexCanServeIt() {
            List<Document> stages = CardSearchOrder.pipeline(new Document(), null, List.of("first_release_date,desc"), 0, 10);

            // The newest-first view: $sort follows $match directly, which idx_first_release_date_id serves.
            assertThat(stages).extracting(stage -> stage.keySet().iterator().next())
                    .containsExactly("$match", "$sort", "$skip", "$limit");
        }

        @Test
        void anEdhrecSortAddsOnlyItsOwnHelper() {
            List<Document> stages = CardSearchOrder.pipeline(new Document(), null, List.of("edhrec_rank"), 0, 10);

            assertThat(stages.get(1).get("$addFields", Document.class).keySet()).containsExactly(CardSearchOrder.EDHREC);
            assertThat(stages.get(5).get("$project")).isEqualTo(new Document(CardSearchOrder.EDHREC, 0));
        }
    }

    @Nested
    class RarityAndSet {

        private final MappingMongoConverter converter = converter();

        // Configured as Spring Boot configures the application's, so dates are simple values.
        private static MappingMongoConverter converter() {
            MongoCustomConversions conversions = new MongoCustomConversions(List.of());
            MongoMappingContext context = new MongoMappingContext();
            context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
            context.afterPropertiesSet();
            MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
            converter.setCustomConversions(conversions);
            converter.afterPropertiesSet();
            return converter;
        }

        @Test
        void rarityFiltersTheCardsOwnFieldAndIsStoredAsItsName() {
            Document match = CardCustomRepositoryImpl.mappedMatch(converter, CardCustomRepositoryImpl.buildCriteria(null,
                    new CardSearchRecord(null, null, null, null, null, null, null, Rarity.RARE, null, null, null)));

            assertThat(match).isEqualTo(Document.parse("{\"$and\": [{\"rarity\": \"RARE\"}]}"));
        }

        @Test
        void aSetCodeIsComparedLowerCasedAsScryfallStoresIt() {
            Document match = CardCustomRepositoryImpl.mappedMatch(converter, CardCustomRepositoryImpl.buildCriteria(null,
                    new CardSearchRecord(null, null, null, null, null, null, null, null, null, null, "MH3")));

            assertThat(match).isEqualTo(Document.parse("{\"$and\": [{\"set_code\": \"mh3\"}]}"));
        }
    }
}
