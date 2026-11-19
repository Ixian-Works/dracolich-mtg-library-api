package dm.dracolich.mtgLibrary.web.controller;

import dm.dracolich.forge.controller.ControllerAdvice;
import dm.dracolich.mtgLibrary.web.repository.custom.SearchOrders;
import dm.dracolich.mtgLibrary.web.service.CardService;
import dm.dracolich.mtgLibrary.web.service.CollectionSetService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sort sent over HTTP, the way the app sends it, reaches each search as the order it names. Spring
 * splits a single {@code sort=key,asc} at the comma, so these go through the real binding.
 */
class SortParameterTest {

    private final CardService cards = mock(CardService.class);
    private final CollectionSetService sets = mock(CollectionSetService.class);
    private final WebTestClient client = WebTestClient.bindToController(new CardController(cards), new CollectionSetController(sets))
            .controllerAdvice(new ControllerAdvice())
            .build();

    @SuppressWarnings("unchecked")
    private List<String> setSortSent(String query) {
        when(sets.searchSets(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)));
        client.post().uri("/sets/search" + query).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
        ArgumentCaptor<List<String>> sort = ArgumentCaptor.forClass(List.class);
        verify(sets).searchSets(any(), any(), sort.capture(), anyInt(), anyInt());
        return sort.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> cardSortSent(String query) {
        when(cards.searchCard(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)));
        client.post().uri("/cards/search" + query).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
        ArgumentCaptor<List<String>> sort = ArgumentCaptor.forClass(List.class);
        verify(cards).searchCard(any(), any(), sort.capture(), anyInt(), anyInt());
        return sort.getValue();
    }

    @Test
    void setSearchReadsAKeyWithADirection() {
        assertThat(SearchOrders.setSort(setSortSent("?sort=release_date,asc")))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "release_date").and(Sort.by(Sort.Direction.ASC, "_id")));
        assertThat(SearchOrders.setSort(setSortSentAgain("?sort=name,desc")))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "name").and(Sort.by(Sort.Direction.ASC, "_id")));
    }

    private List<String> setSortSentAgain(String query) {
        org.mockito.Mockito.clearInvocations(sets);
        return setSortSent(query);
    }

    @Test
    void cardSearchReadsAKeyWithADirection() {
        assertThat(SearchOrders.cardSort(cardSortSent("?sort=mana_value,desc")))
                .isEqualTo(new Document("default_face.gameplay_property.mana_value", -1).append("_id", 1));
    }

    @Test
    void cardSearchReadsTwoKeysWithDirections() {
        assertThat(SearchOrders.cardSort(cardSortSent("?sort=mana_value,desc&sort=name,asc")))
                .isEqualTo(new Document("default_face.gameplay_property.mana_value", -1).append("name", 1).append("_id", 1));
    }

    @Test
    void cardSearchReadsAKeyWithNoDirection() {
        assertThat(SearchOrders.cardSort(cardSortSent("?sort=name")))
                .isEqualTo(new Document("name", 1).append("_id", 1));
    }
}
