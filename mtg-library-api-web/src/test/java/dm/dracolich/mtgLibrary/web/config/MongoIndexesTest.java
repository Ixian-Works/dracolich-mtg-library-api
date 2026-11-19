package dm.dracolich.mtgLibrary.web.config;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which indexes start-up creates. Nothing here talks to a database. */
class MongoIndexesTest {

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

    private List<String> indexes() {
        ReactiveMongoTemplate template = mock(ReactiveMongoTemplate.class);
        when(template.getConverter()).thenReturn(converter());
        return new MongoIndexes(template).allIndexes().stream()
                .map(e -> e.getKey().getSimpleName() + " " + e.getValue().getIndexKeys().toJson()
                        + (Boolean.TRUE.equals(e.getValue().getIndexOptions().get("unique")) ? " unique" : ""))
                .toList();
    }

    @Test
    void theKeysTheSeederUpsertsOnAreUniqueIndexes() {
        assertThat(indexes()).contains(
                "CardEntity {\"oracle_id\": 1} unique",
                "ArtPropertyEntity {\"scryfall_id\": 1} unique",
                "SymbolEntity {\"symbol\": 1} unique",
                "CardLayoutEntity {\"code\": 1} unique",
                "FrameEffectEntity {\"code\": 1} unique",
                "SubtypeEntity {\"code\": 1} unique");
    }

    @Test
    void aPagesArtIsFoundByCardId() {
        assertThat(indexes()).contains("ArtPropertyEntity {\"card_id\": 1}");
    }

    @Test
    void exactNamesAndTheNewestViewHaveTheirOwnIndexes() {
        assertThat(indexes()).contains(
                "CardEntity {\"name_keys\": 1}",
                "CardEntity {\"first_release_date\": -1, \"_id\": 1}");
    }

    @Test
    void theExtraIndexesAreNamed() {
        assertThat(MongoIndexes.extraIndexes().get(dm.dracolich.mtgLibrary.web.entity.CardEntity.class))
                .extracting(index -> index.getIndexOptions().get("name"))
                .containsExactly("idx_name_keys", "idx_first_release_date_id", "idx_commander_pairings");
    }
}
