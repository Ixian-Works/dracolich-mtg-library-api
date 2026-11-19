package dm.dracolich.mtgLibrary.web.config;

import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.entity.CardLayoutEntity;
import dm.dracolich.mtgLibrary.web.entity.CollectionSetEntity;
import dm.dracolich.mtgLibrary.web.entity.FrameEffectEntity;
import dm.dracolich.mtgLibrary.web.entity.GameFormatEntity;
import dm.dracolich.mtgLibrary.web.entity.SubtypeEntity;
import dm.dracolich.mtgLibrary.web.entity.SymbolEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates the collections' indexes at startup. Spring Data hasn't created {@code @Indexed} indexes on
 * its own since version 3, so without this the collections have only {@code _id}, and every seeder
 * upsert and art lookup scans its collection.
 *
 * <p>Creating an index that already exists is a no-op, so this is safe on every start. A failure
 * (for instance a unique index over data that already holds duplicates) is logged and the others are
 * still created: the service then runs as it did before, only slower.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexes {

    /** Entities whose {@code @Indexed} annotations are created. */
    static final List<Class<?>> ANNOTATED = List.of(
            CardEntity.class, ArtPropertyEntity.class, CollectionSetEntity.class, SymbolEntity.class,
            CardLayoutEntity.class, FrameEffectEntity.class, SubtypeEntity.class, GameFormatEntity.class);

    private final ReactiveMongoTemplate mongoTemplate;

    /** Indexes no annotation declares, by entity. */
    static Map<Class<?>, List<IndexDefinition>> extraIndexes() {
        return Map.of(CardEntity.class, List.of(
                // Exact-name lookup across the card's name and both faces' names, in one query.
                new Index().on(CardEntity.NAME_KEYS, Sort.Direction.ASC).named("idx_name_keys"),
                // The cards screen's newest-first view, with the _id tiebreak every search sort ends on.
                new Index().on("first_release_date", Sort.Direction.DESC).on("_id", Sort.Direction.ASC)
                        .named("idx_first_release_date_id"),
                // A set's cards in collector-number order.
                new Index().on("set_code", Sort.Direction.ASC).on("collector_order", Sort.Direction.ASC)
                        .on("collector_number", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                        .named("idx_set_collector_number")),
                CollectionSetEntity.class, List.of(
                // Set search newest first, the default, and by name.
                new Index().on("release_date", Sort.Direction.DESC).on("_id", Sort.Direction.ASC)
                        .named("idx_release_date_id"),
                new Index().on("name", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                        .named("idx_set_name_id")));
    }

    /** Every index to create, by entity: the annotated ones, then the extra ones. */
    List<Map.Entry<Class<?>, IndexDefinition>> allIndexes() {
        IndexResolver resolver = IndexResolver.create(mongoTemplate.getConverter().getMappingContext());
        List<Map.Entry<Class<?>, IndexDefinition>> all = new ArrayList<>();
        for (Class<?> entity : ANNOTATED) {
            resolver.resolveIndexFor(entity).forEach(index -> all.add(Map.entry(entity, index)));
        }
        extraIndexes().forEach((entity, indexes) -> indexes.forEach(index -> all.add(Map.entry(entity, index))));
        return all;
    }

    /** Creates every index, one at a time. Completes when all were tried. */
    public Mono<Void> ensureAll() {
        return Flux.fromIterable(allIndexes())
                .concatMap(entry -> mongoTemplate.indexOps(entry.getKey())
                        .createIndex(entry.getValue())
                        .doOnNext(name -> log.info("Ensured index {} on {}.", name, entry.getKey().getSimpleName()))
                        .onErrorResume(e -> {
                            log.warn("Could not create index {} on {}: {}", entry.getValue().getIndexKeys(),
                                    entry.getKey().getSimpleName(), e.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }
}
