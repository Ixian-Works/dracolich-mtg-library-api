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
                .switchIfEmpty(Mono.defer(() -> repo.findByNameIgnoreCase(name)))
                .switchIfEmpty(Mono.defer(() -> repo.findByDefaultFaceNameIgnoreCase(name)))
                .switchIfEmpty(Mono.defer(() -> repo.findByFlippedFaceNameIgnoreCase(name)))
                .switchIfEmpty(Mono.error(notFound("card", "name", name)))
                .flatMap(this::withDefaultArt);
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
