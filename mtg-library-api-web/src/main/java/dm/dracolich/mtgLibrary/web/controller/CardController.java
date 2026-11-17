package dm.dracolich.mtgLibrary.web.controller;

import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRecord;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRequest;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.web.service.CardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import dm.dracolich.mtgLibrary.web.helpers.PageBounds;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("cards")
@Tag(name = "Cards")
@RequiredArgsConstructor
public class CardController {
    private final CardService service;

    @Operation(summary = "Look up many cards at once",
            description = "Up to 100 identifiers, each an id or an exact name (a face's name finds its card). "
                    + "Returns the cards found in request order, and the identifiers that matched nothing in not_found.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cards looked up",
                    content = @Content(schema = @Schema(implementation = CardCollectionRecord.class))),
            @ApiResponse(responseCode = "400", description = "No identifiers, more than 100, or one with neither or both of id and name")
    })
    @PostMapping(path = {"/collection"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CardCollectionRecord> fetchCollection(@RequestBody CardCollectionRequest request) {
        return service.fetchCollection(request);
    }

    @Operation(summary = "Search all cards", description = "Returns cards by name, filters")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cards fetched successfully",
                    content = @Content(schema = @Schema(implementation = CardDto.class)))
    })
    @PostMapping(path = {"/search"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Page<CardDto>> searchCards(@RequestParam(required = false) String name,
                                           @RequestBody CardSearchRecord filters,
                                           @Parameter(description = "Sort keys, repeatable: name, first_release_date, mana_value or edhrec_rank, each optionally followed by ,asc or ,desc. Without one, a search with a name puts the best name matches first, then the most played.")
                                           @RequestParam(required = false) List<String> sort,
                                           @RequestParam(defaultValue = PageBounds.DEFAULT_PAGE) int page,
                                           @RequestParam(defaultValue = PageBounds.DEFAULT_SIZE) int size) {
        return service.searchCard(name, filters, sort, page, size);
    }

    @Operation(summary = "Search all cards", description = "Returns cards by name, filters")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cards fetched successfully",
                    content = @Content(schema = @Schema(implementation = CardDto.class)))
    })
    @PostMapping(path = {"/random"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CardDto> random(@RequestBody CardSearchRecord filters) {
        return service.fetchRandomCard(filters);
    }

    @Operation(summary = "Fetch card by id", description = "Returns card by id")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Card fetched successfully",
                    content = @Content(schema = @Schema(implementation = CardDto.class)))
    })
    @GetMapping(path = {"/{id}"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CardDto> fetchCardById(@PathVariable String id) {
        return service.fetchCardById(id);
    }

    @Operation(summary = "Fetch card by id", description = "Returns card by id")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Card fetched successfully",
                    content = @Content(schema = @Schema(implementation = CardDto.class)))
    })
    @GetMapping(path = {""}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CardDto> fetchCardByName(@RequestParam String name) {
        return service.fetchCardByName(name);
    }
}
