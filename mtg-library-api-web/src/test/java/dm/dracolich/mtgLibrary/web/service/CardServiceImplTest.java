package dm.dracolich.mtgLibrary.web.service;

import dm.dracolich.mtgLibrary.dto.ArtPropertyDto;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.mapper.ArtPropertyMapper;
import dm.dracolich.mtgLibrary.web.mapper.CardMapper;
import dm.dracolich.mtgLibrary.web.repository.ArtPropertyRepository;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
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
}
