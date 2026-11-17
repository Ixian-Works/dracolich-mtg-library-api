package dm.dracolich.mtgLibrary.web.controller;

import dm.dracolich.forge.controller.ControllerAdvice;
import dm.dracolich.mtgLibrary.web.service.CardService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Since forge 8.1.3 a request Spring rejects keeps its own status and DMD043, instead of a 500. */
class BadRequestTest {

    private final CardService service = mock(CardService.class);
    private final WebTestClient client = WebTestClient.bindToController(new CardController(service))
            .controllerAdvice(new ControllerAdvice())
            .build();

    @Test
    void aMalformedSearchBodyIs400WithDmd043() {
        client.post().uri("/cards/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ this is not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].error").isEqualTo("DMD043");

        verifyNoInteractions(service);
    }

    @Test
    void aPageThatIsNotANumberIs400WithDmd043() {
        client.post().uri("/cards/search?page=first")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].error").isEqualTo("DMD043");
    }
}
