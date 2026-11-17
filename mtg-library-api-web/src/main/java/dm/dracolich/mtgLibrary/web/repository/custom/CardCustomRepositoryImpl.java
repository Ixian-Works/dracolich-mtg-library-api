package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.dto.enums.ColorMatch;
import dm.dracolich.mtgLibrary.dto.enums.Legality;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class CardCustomRepositoryImpl implements CardCustomRepository {
    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public Mono<Page<CardEntity>> findByFilters(String name, CardSearchRecord search, List<String> sort,
                                                int page, int size) {
        Criteria combined = buildCriteria(name, search);
        // Parsed before any query runs, so an unknown sort is a 400 and costs nothing.
        CardSearchOrder.sort(sort, name);

        // The $match is mapped the way find() maps a query, so enums and field names are written
        // exactly as they are stored. The stages after it are plain documents.
        Document match = mappedMatch(mongoTemplate.getConverter(), combined);
        List<AggregationOperation> stages = CardSearchOrder.pipeline(match, name, sort, page, size).stream()
                .<AggregationOperation>map(stage -> context -> stage)
                .toList();
        PageRequest pageRequest = PageRequest.of(page, size);

        return mongoTemplate.count(Query.query(combined), CardEntity.class)
                .flatMap(total -> mongoTemplate.aggregate(Aggregation.newAggregation(stages), "cards", CardEntity.class)
                        .collectList()
                        .map(list -> new PageImpl<>(list, pageRequest, total)));
    }

    // Package-private and static so the tests can check the mapping without a database.
    static Document mappedMatch(MongoConverter converter, Criteria criteria) {
        return new QueryMapper(converter).getMappedObject(
                Query.query(criteria).getQueryObject(),
                converter.getMappingContext().getPersistentEntity(CardEntity.class));
    }

    @Override
    public Mono<CardEntity> findRandom(CardSearchRecord search) {
        Criteria combined = buildCriteria(null, search);

        Aggregation aggregation;
        if (combined.equals(new Criteria())) {
            aggregation = Aggregation.newAggregation(Aggregation.sample(1));
        } else {
            aggregation = Aggregation.newAggregation(
                    Aggregation.match(combined),
                    Aggregation.sample(1)
            );
        }

        return mongoTemplate.aggregate(aggregation, "cards", CardEntity.class).next();
    }

    // Package-private and static so the tests can read the query it builds without a database.
    static Criteria buildCriteria(String name, CardSearchRecord search) {
        List<Criteria> filters = new ArrayList<>();

        if (name != null && !name.isBlank())
            filters.add(Criteria.where("name").regex(name, "i"));

        if (search != null) {
            if (search.oracleText() != null && !search.oracleText().isBlank())
                filters.add(eitherFace("oracle_text").regex(search.oracleText(), "i"));

            Criteria colorIdentity = colorIdentityFilter(search.colorIdentity(), search.colorIdentityMatch());
            if (colorIdentity != null)
                filters.add(colorIdentity);

            Criteria colors = colorsFilter(search.colors(), search.colorsMatch());
            if (colors != null)
                filters.add(colors);

            if (search.types() != null && !search.types().isEmpty())
                filters.add(eitherFace("types").all(enumNames(search.types())));

            if (search.subtypes() != null && !search.subtypes().isEmpty())
                filters.add(eitherFace("subtypes").all(search.subtypes()));

            if (search.keywords() != null && !search.keywords().isEmpty())
                filters.add(Criteria.where("keywords").all(search.keywords()));

            if (search.legalIn() != null)
                filters.add(Criteria.where("legalities." + search.legalIn().name()).is(Legality.LEGAL));

            if (search.minManaValue() != null || search.maxManaValue() != null) {
                Criteria defaultMana = Criteria.where("default_face.gameplay_property.mana_value");
                Criteria flippedMana = Criteria.where("flipped_face.gameplay_property.mana_value");
                if (search.minManaValue() != null) {
                    defaultMana = defaultMana.gte(search.minManaValue());
                    flippedMana = flippedMana.gte(search.minManaValue());
                }
                if (search.maxManaValue() != null) {
                    defaultMana = defaultMana.lte(search.maxManaValue());
                    flippedMana = flippedMana.lte(search.maxManaValue());
                }
                filters.add(new Criteria().orOperator(defaultMana, flippedMana));
            }

            if (search.rarity() != null)
                filters.add(Criteria.where("rarity").is(search.rarity()));

            if (search.setCode() != null && !search.setCode().isBlank())
                filters.add(Criteria.where("set_code").is(search.setCode().toLowerCase(Locale.ROOT)));
        }

        return filters.isEmpty()
                ? new Criteria()
                : new Criteria().andOperator(filters.toArray(Criteria[]::new));
    }

    private static final String DEFAULT_FACE_IDENTITY = "default_face.gameplay_property.color_identity";
    private static final String DEFAULT_FACE_COLORS = "default_face.gameplay_property.colors";
    private static final String FLIPPED_FACE_COLORS = "flipped_face.gameplay_property.colors";

    /**
     * A card has one colour identity, whatever its number of faces, and the seeder keeps it on
     * the default face. EXACTLY and AT_MOST read it there. INCLUDES is the query it has always
     * been, on either face; the flipped face never holds an identity, so it reads the same value.
     *
     * <p>AT_MOST only matches a card that has an identity stored. Without that condition a card
     * with none would count as "no colour outside the set" and pass every search.</p>
     */
    private static Criteria colorIdentityFilter(Set<Color> selected, ColorMatch match) {
        if (selected == null) return null;
        return switch (match != null ? match : ColorMatch.INCLUDES) {
            case INCLUDES -> selected.isEmpty()
                    ? null
                    : eitherFaceGameplay("color_identity").all(enumNames(selected));
            case EXACTLY -> exactly(DEFAULT_FACE_IDENTITY, coloured(selected));
            case AT_MOST -> noneOutside(DEFAULT_FACE_IDENTITY, coloured(selected), true);
        };
    }

    /**
     * Colours belong to a face. INCLUDES and EXACTLY match a card when either face matches.
     * AT_MOST needs every face the card has to stay inside the selection: a card with a white
     * front and a black back is not "at most white".
     */
    private static Criteria colorsFilter(Set<Color> selected, ColorMatch match) {
        if (selected == null) return null;
        return switch (match != null ? match : ColorMatch.INCLUDES) {
            case INCLUDES -> selected.isEmpty()
                    ? null
                    : eitherFaceGameplay("colors").all(enumNames(selected));
            case EXACTLY -> new Criteria().orOperator(
                    exactly(DEFAULT_FACE_COLORS, coloured(selected)),
                    exactly(FLIPPED_FACE_COLORS, coloured(selected)));
            case AT_MOST -> new Criteria().andOperator(
                    noneOutside(DEFAULT_FACE_COLORS, coloured(selected), true),
                    // A card with one face has no flipped face, and that must not rule it out.
                    noneOutside(FLIPPED_FACE_COLORS, coloured(selected), false));
        };
    }

    // The colours of a selection as they are stored. C is how a caller says "colourless", and no
    // card stores it among its colours: a colourless card has an empty list.
    private static List<String> coloured(Set<Color> selected) {
        return selected.stream().filter(color -> color != Color.C).map(Enum::name).toList();
    }

    // The list holds these colours and nothing else. $all with an empty list matches no document,
    // so "exactly no colour" is the size alone.
    private static Criteria exactly(String path, List<String> colors) {
        return colors.isEmpty()
                ? Criteria.where(path).size(0)
                : Criteria.where(path).all(colors).size(colors.size());
    }

    // No element of the list is outside these colours. An empty list passes, which is what makes
    // a colourless card fit every commander. A missing list passes too unless it has to exist.
    private static Criteria noneOutside(String path, List<String> colors, boolean mustExist) {
        Criteria list = Criteria.where(path);
        if (mustExist) list = list.exists(true);
        return list.not().elemMatch(new Criteria().nin(colors));
    }

    private static EitherFaceBuilder eitherFace(String field) {
        return new EitherFaceBuilder("default_face." + field, "flipped_face." + field);
    }

    private static EitherFaceBuilder eitherFaceGameplay(String field) {
        return new EitherFaceBuilder(
                "default_face.gameplay_property." + field,
                "flipped_face.gameplay_property." + field
        );
    }

    private static List<String> enumNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).collect(Collectors.toList());
    }

    private record EitherFaceBuilder(String defaultPath, String flippedPath) {
        Criteria regex(String pattern, String options) {
            return new Criteria().orOperator(
                    Criteria.where(defaultPath).regex(pattern, options),
                    Criteria.where(flippedPath).regex(pattern, options)
            );
        }

        Criteria all(Collection<?> values) {
            return new Criteria().orOperator(
                    Criteria.where(defaultPath).all(values),
                    Criteria.where(flippedPath).all(values)
            );
        }
    }
}
