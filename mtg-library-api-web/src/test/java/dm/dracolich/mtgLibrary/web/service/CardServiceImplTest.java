package dm.dracolich.mtgLibrary.web.service;

import dm.dracolich.mtgLibrary.dto.ArtPropertyDto;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRequest;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.mapper.ArtPropertyMapper;
import dm.dracolich.mtgLibrary.web.mapper.CardMapper;
import dm.dracolich.mtgLibrary.web.repository.ArtPropertyRepository;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardServiceImplTest {

    private final CardRepository cards = mock(CardRepository.class);
    private final ArtPropertyRepository art = mock(ArtPropertyRepository.class);
    private final CardMapper mapper = mock(CardMapper.class);
    private final ArtPropertyMapper artMapper = mock(ArtPropertyMapper.class);
    private final CardServiceImpl service = new CardServiceImpl(cards, art, mapper, artMapper);

    @BeforeEach
    void mappers() {
        when(mapper.entityToDto(any(CardEntity.class))).thenAnswer(i -> {
            CardDto dto = new CardDto();
            dto.setId(i.<CardEntity>getArgument(0).getId());
            return dto;
        });
        when(mapper.entityToDtoWithArt(any(CardEntity.class), any(ArtPropertyDto.class))).thenCallRealMethod();
        when(artMapper.entityToDto(any(ArtPropertyEntity.class))).thenAnswer(i -> new ArtPropertyDto());
    }

    private static CardEntity card(String id, String name) {
        return CardEntity.builder().id(id).name(name).build();
    }

    private static ArtPropertyEntity art(String cardId, double price) {
        return ArtPropertyEntity.builder().cardId(cardId).prices(Map.of("usd", price)).build();
    }

    @Test
    void aSearchPageFetchesAllItsArtInOneQuery() {
        List<CardEntity> page = List.of(card("a", "A"), card("b", "B"), card("c", "C"));
        when(cards.findByFilters(isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new PageImpl<>(page, PageRequest.of(0, 20), 3)));
        when(art.findByCardIdIn(anyCollection())).thenReturn(Flux.just(art("a", 2), art("a", 1), art("b", 5)));

        StepVerifier.create(service.searchCard(null, null, null, 0, 20))
                .assertNext(result -> {
                    assertThat(result.getContent()).extracting(CardDto::getId).containsExactly("a", "b", "c");
                    assertThat(result.getTotalElements()).isEqualTo(3);
                })
                .verifyComplete();

        verify(art, times(1)).findByCardIdIn(anyCollection());
        verify(art, never()).findByCardId(anyString());
        verify(artMapper, times(2)).entityToDto(any(ArtPropertyEntity.class));
    }

    @Test
    void anEmptyPageMakesNoArtQuery() {
        when(cards.findByFilters(isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)));

        StepVerifier.create(service.searchCard(null, null, null, 0, 20))
                .assertNext(result -> assertThat(result.getContent()).isEmpty())
                .verifyComplete();

        verify(art, never()).findByCardIdIn(any(Collection.class));
    }

    @Test
    void theCheapestPrintingIsChosen() {
        ArtPropertyEntity cheap = art("a", 0.5);
        assertThat(CardServiceImpl.chooseArt(List.of(art("a", 3), cheap, art("a", 1)))).isSameAs(cheap);
    }

    @Test
    void anExactNameIsOneIndexedQuery_andAWholeNameBeatsAFaceName() {
        CardEntity split = card("split", "Fire // Ice");
        CardEntity fire = card("fire", "Fire");
        when(cards.findByNameKeys("fire")).thenReturn(Flux.just(split, fire));
        when(art.findByCardId("fire")).thenReturn(Flux.empty());

        StepVerifier.create(service.fetchCardByName("  FIRE "))
                .assertNext(dto -> assertThat(dto.getId()).isEqualTo("fire"))
                .verifyComplete();

        verify(cards, never()).findByNameIgnoreCase(anyString());
    }

    @Test
    void aCardImportedBeforeNameKeysIsStillFound() {
        when(cards.findByNameKeys("lightning bolt")).thenReturn(Flux.empty());
        when(cards.findByNameIgnoreCase("Lightning Bolt")).thenReturn(Mono.just(card("bolt", "Lightning Bolt")));
        when(cards.findByDefaultFaceNameIgnoreCase(anyString())).thenReturn(Mono.empty());
        when(cards.findByFlippedFaceNameIgnoreCase(anyString())).thenReturn(Mono.empty());
        when(art.findByCardId("bolt")).thenReturn(Flux.empty());

        StepVerifier.create(service.fetchCardByName("Lightning Bolt"))
                .assertNext(dto -> assertThat(dto.getId()).isEqualTo("bolt"))
                .verifyComplete();
    }

    @Nested
    class CollectionLookup {

        private CardEntity keyed(String id, String name, String... keys) {
            CardEntity card = card(id, name);
            card.setNameKeys(new java.util.LinkedHashSet<>(List.of(keys)));
            return card;
        }

        @BeforeEach
        void noArt() {
            when(art.findByCardIdIn(anyCollection())).thenReturn(Flux.empty());
        }

        @Test
        void ahundredNamesAreTwoQueriesForCardsAndOneForArt() {
            List<CardIdentifier> identifiers = new java.util.ArrayList<>();
            List<CardEntity> library = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                identifiers.add(CardIdentifier.byName("Card " + i));
                library.add(keyed("c" + i, "Card " + i, "card " + i));
            }
            when(cards.findByNameKeysIn(anyCollection())).thenReturn(Flux.fromIterable(library));

            StepVerifier.create(service.fetchCollection(new CardCollectionRequest(identifiers)))
                    .assertNext(result -> {
                        assertThat(result.cards()).hasSize(100);
                        assertThat(result.cards().getFirst().getId()).isEqualTo("c0");
                        assertThat(result.notFound()).isEmpty();
                    })
                    .verifyComplete();

            verify(cards, times(1)).findByNameKeysIn(anyCollection());
            verify(cards, never()).findAllById(any(Iterable.class));
            verify(cards, never()).findByNameKeys(anyString());
            verify(art, times(1)).findByCardIdIn(anyCollection());
        }

        @Test
        void unknownNamesComeBackInNotFound_inRequestOrder_andTheRestPairUp() {
            when(cards.findAllById(any(Iterable.class))).thenReturn(Flux.just(card("bolt", "Lightning Bolt")));
            when(cards.findByNameKeysIn(anyCollection())).thenReturn(Flux.just(keyed("ice", "Fire // Ice", "fire // ice", "fire", "ice")));
            when(cards.findByNameIgnoreCase(anyString())).thenReturn(Mono.empty());
            when(cards.findByDefaultFaceNameIgnoreCase(anyString())).thenReturn(Mono.empty());
            when(cards.findByFlippedFaceNameIgnoreCase(anyString())).thenReturn(Mono.empty());

            List<CardIdentifier> identifiers = List.of(
                    CardIdentifier.byName("Nope One"), CardIdentifier.byId("bolt"), CardIdentifier.byName("ICE"),
                    CardIdentifier.byName("Nope Two"), CardIdentifier.byId("missing"), CardIdentifier.byName("Nope Three"));

            StepVerifier.create(service.fetchCollection(new CardCollectionRequest(identifiers)))
                    .assertNext(result -> {
                        assertThat(result.cards()).extracting(CardDto::getId).containsExactly("bolt", "ice");
                        assertThat(result.notFound()).containsExactly(
                                CardIdentifier.byName("Nope One"), CardIdentifier.byName("Nope Two"),
                                CardIdentifier.byId("missing"), CardIdentifier.byName("Nope Three"));
                    })
                    .verifyComplete();
        }

        @Test
        void aCardImportedBeforeNameKeysIsFoundByTheFallback() {
            when(cards.findByNameKeysIn(anyCollection())).thenReturn(Flux.empty());
            when(cards.findByNameIgnoreCase("Old Card")).thenReturn(Mono.just(card("old", "Old Card")));

            StepVerifier.create(service.fetchCollection(new CardCollectionRequest(List.of(CardIdentifier.byName("Old Card")))))
                    .assertNext(result -> assertThat(result.cards()).extracting(CardDto::getId).containsExactly("old"))
                    .verifyComplete();
        }

        @Test
        void aRepeatedIdentifierRepeatsItsCard() {
            when(cards.findAllById(any(Iterable.class))).thenReturn(Flux.just(card("bolt", "Lightning Bolt")));

            StepVerifier.create(service.fetchCollection(new CardCollectionRequest(List.of(
                            CardIdentifier.byId("bolt"), CardIdentifier.byId("bolt")))))
                    .assertNext(result -> assertThat(result.cards()).extracting(CardDto::getId).containsExactly("bolt", "bolt"))
                    .verifyComplete();
        }

        @Test
        void aBadRequestIs400WithNoQuery() {
            List<CardIdentifier> tooMany = java.util.Collections.nCopies(101, CardIdentifier.byId("x"));
            for (CardCollectionRequest bad : List.of(
                    new CardCollectionRequest(List.of()),
                    new CardCollectionRequest(tooMany),
                    new CardCollectionRequest(List.of(new CardIdentifier("a", "b"))),
                    new CardCollectionRequest(List.of(new CardIdentifier(" ", null))))) {
                StepVerifier.create(service.fetchCollection(bad))
                        .expectErrorSatisfies(e -> {
                            assertThat(e).isInstanceOf(dm.dracolich.forge.exception.ResponseException.class);
                            var response = (dm.dracolich.forge.exception.ResponseException) e;
                            assertThat(response.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                            assertThat(response.getErrors()).extracting("error").containsExactly("DMD047");
                        })
                        .verify();
            }
            verify(cards, never()).findAllById(any(Iterable.class));
            verify(cards, never()).findByNameKeysIn(anyCollection());
        }
    }
}
