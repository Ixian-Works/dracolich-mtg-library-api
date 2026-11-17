package dm.dracolich.mtgLibrary.web.service;

import dm.dracolich.mtgLibrary.dto.ArtPropertyDto;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.mapper.ArtPropertyMapper;
import dm.dracolich.mtgLibrary.web.mapper.CardMapper;
import dm.dracolich.mtgLibrary.web.repository.ArtPropertyRepository;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.error.ApiError;
import dm.dracolich.mtgLibrary.dto.error.ErrorCodes;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRequest;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRecord;
import dm.dracolich.mtgLibrary.web.helpers.PageBounds;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;

import static dm.dracolich.mtgLibrary.web.helpers.ErrorUtil.notFound;

@Service
@RequiredArgsConstructor
public class CardServiceImpl implements CardService {
    private final CardRepository repo;
    private final ArtPropertyRepository artRepo;
    private final CardMapper mapper;
    private final ArtPropertyMapper artMapper;

    @Override
    public Mono<Page<CardDto>> searchCard(String name, CardSearchRecord cardSearchRecord, List<String> sort,
                                         int page, int size) {
        PageBounds.check(page, size);
        return repo.findByFilters(name, cardSearchRecord, sort, page, size)
                .flatMap(p -> withDefaultArt(p.getContent())
                        .map(content -> (Page<CardDto>) new PageImpl<>(content, p.getPageable(), p.getTotalElements())));
    }

    @Override
    public Mono<CardDto> fetchRandomCard(CardSearchRecord cardSearchRecord) {
        return repo.findRandom(cardSearchRecord)
                .flatMap(this::withDefaultArt);
    }

    @Override
    public Mono<CardDto> fetchCardById(String id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(notFound("card", "id", id)))
                .flatMap(this::withDefaultArt);
    }

    @Override
    public Mono<CardDto> fetchCardByName(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        // One indexed query across the card's name and both faces' names. Cards imported before
        // name_keys existed have none, so a miss falls back to the three case-insensitive scans.
        return repo.findByNameKeys(key)
                .collectList()
                .flatMap(matches -> Mono.justOrEmpty(preferWholeName(matches, key)))
                .switchIfEmpty(Mono.defer(() -> findByNameWithoutKeys(name)))
                .switchIfEmpty(Mono.error(notFound("card", "name", name)))
                .flatMap(this::withDefaultArt);
    }

    /** The three case-insensitive scans, for cards imported before name_keys existed. */
    private Mono<CardEntity> findByNameWithoutKeys(String name) {
        return repo.findByNameIgnoreCase(name)
                .switchIfEmpty(Mono.defer(() -> repo.findByDefaultFaceNameIgnoreCase(name)))
                .switchIfEmpty(Mono.defer(() -> repo.findByFlippedFaceNameIgnoreCase(name)));
    }

    @Override
    public Mono<CardCollectionRecord> fetchCollection(CardCollectionRequest request) {
        List<CardIdentifier> identifiers;
        try {
            identifiers = checkCollection(request);
        } catch (ResponseException e) {
            return Mono.error(e);
        }
        Set<String> ids = identifiers.stream().map(CardIdentifier::id).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> keys = identifiers.stream().map(CardIdentifier::name).filter(Objects::nonNull)
                .map(CardServiceImpl::nameKey).collect(Collectors.toCollection(LinkedHashSet::new));

        // Two queries for the whole request: every id, and every name through name_keys.
        Mono<Map<String, CardEntity>> byId = ids.isEmpty() ? Mono.just(Map.of())
                : repo.findAllById(ids).collectMap(CardEntity::getId);
        Mono<List<CardEntity>> byKey = keys.isEmpty() ? Mono.just(List.of())
                : repo.findByNameKeysIn(keys).collectList();

        return Mono.zip(byId, byKey)
                .flatMap(found -> {
                    Map<CardIdentifier, CardEntity> resolved = new LinkedHashMap<>();
                    for (CardIdentifier identifier : identifiers) {
                        CardEntity card = identifier.id() != null
                                ? found.getT1().get(identifier.id())
                                : preferWholeName(withKey(found.getT2(), nameKey(identifier.name())),
                                        nameKey(identifier.name())).orElse(null);
                        if (card != null) resolved.put(identifier, card);
                    }
                    // Names still missing may belong to cards imported before name_keys existed.
                    return Flux.fromIterable(identifiers)
                            .filter(identifier -> identifier.name() != null && !resolved.containsKey(identifier))
                            .distinct()
                            .concatMap(identifier -> findByNameWithoutKeys(identifier.name().trim())
                                    .doOnNext(card -> resolved.put(identifier, card)))
                            .then(Mono.just(resolved));
                })
                .flatMap(resolved -> {
                    List<CardEntity> distinctCards = resolved.values().stream()
                            .collect(Collectors.toMap(CardEntity::getId, card -> card, (a, b) -> a, LinkedHashMap::new))
                            .values().stream().toList();
                    return withDefaultArt(distinctCards).map(dtos -> {
                        Map<String, CardDto> dtoById = dtos.stream()
                                .collect(Collectors.toMap(CardDto::getId, dto -> dto, (a, b) -> a));
                        List<CardDto> cards = new ArrayList<>();
                        List<CardIdentifier> notFound = new ArrayList<>();
                        for (CardIdentifier identifier : identifiers) {
                            CardEntity card = resolved.get(identifier);
                            if (card == null) notFound.add(identifier);
                            else cards.add(dtoById.get(card.getId()));
                        }
                        return new CardCollectionRecord(cards, notFound);
                    });
                });
    }

    /** The identifiers of a valid request; a 400 otherwise. */
    static List<CardIdentifier> checkCollection(CardCollectionRequest request) {
        List<CardIdentifier> identifiers = request != null ? request.identifiers() : null;
        if (identifiers == null || identifiers.isEmpty()) {
            throw invalidCollection("give at least one identifier");
        }
        if (identifiers.size() > CardCollectionRequest.MAX_IDENTIFIERS) {
            throw invalidCollection("at most " + CardCollectionRequest.MAX_IDENTIFIERS
                    + " identifiers per request, not " + identifiers.size());
        }
        List<CardIdentifier> clean = new ArrayList<>();
        for (int i = 0; i < identifiers.size(); i++) {
            CardIdentifier identifier = identifiers.get(i);
            boolean hasId = identifier != null && identifier.id() != null && !identifier.id().isBlank();
            boolean hasName = identifier != null && identifier.name() != null && !identifier.name().isBlank();
            if (hasId == hasName) {
                throw invalidCollection("identifier " + i + " needs exactly one of id or name");
            }
            clean.add(hasId ? CardIdentifier.byId(identifier.id().trim()) : CardIdentifier.byName(identifier.name().trim()));
        }
        return clean;
    }

    private static ResponseException invalidCollection(String reason) {
        return new ResponseException(ErrorCodes.DMD047.format(reason),
                List.of(new ApiError(ErrorCodes.DMD047)), HttpStatus.BAD_REQUEST);
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static List<CardEntity> withKey(List<CardEntity> cards, String key) {
        return cards.stream()
                .filter(card -> card.getNameKeys() != null && card.getNameKeys().contains(key))
                .toList();
    }

    /** A card whose own name matches beats one matched by a face's name. */
    static Optional<CardEntity> preferWholeName(List<CardEntity> matches, String key) {
        return matches.stream()
                .filter(card -> card.getName() != null && card.getName().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .or(() -> matches.stream().findFirst());
    }

    private Mono<CardDto> withDefaultArt(CardEntity card) {
        return artRepo.findByCardId(card.getId())
                .collectList()
                .map(arts -> toDto(card, arts));
    }

    /** A page of cards with their art, from one query for all of the page's art. */
    private Mono<List<CardDto>> withDefaultArt(List<CardEntity> cards) {
        if (cards.isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> ids = cards.stream().map(CardEntity::getId).toList();
        return artRepo.findByCardIdIn(ids)
                .collectMultimap(ArtPropertyEntity::getCardId)
                .map(artByCard -> cards.stream()
                        .map(card -> toDto(card, List.copyOf(artByCard.getOrDefault(card.getId(), List.of()))))
                        .toList());
    }

    private CardDto toDto(CardEntity card, List<ArtPropertyEntity> arts) {
        if (arts.isEmpty()) {
            return mapper.entityToDto(card);
        }
        ArtPropertyDto chosenDto = artMapper.entityToDto(chooseArt(arts));
        return mapper.entityToDtoWithArt(card, chosenDto);
    }

    /** The cheapest printing, or the first when none has a price. */
    static ArtPropertyEntity chooseArt(List<ArtPropertyEntity> arts) {
        return arts.stream()
                .min(Comparator.comparing(CardServiceImpl::cheapestPrice,
                        Comparator.nullsLast(Double::compareTo)))
                .orElse(arts.getFirst());
    }

    private static Double cheapestPrice(ArtPropertyEntity art) {
        Map<String, Double> prices = art.getPrices();
        if (prices == null || prices.isEmpty()) {
            return null;
        }
        return prices.values().stream()
                .filter(v -> v != null && v > 0)
                .min(Double::compareTo)
                .orElse(null);
    }
}
