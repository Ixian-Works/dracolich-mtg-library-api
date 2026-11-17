package dm.dracolich.mtgLibrary.dto.records;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.mtgLibrary.dto.CardDto;

import java.util.List;

/**
 * The answer to a collection request. {@code cards} holds one card for each identifier that was
 * found, in the request's order, repeated if the request repeated it. {@code not_found} holds the
 * identifiers that matched nothing, also in order. So a caller can pair each identifier with its card
 * by walking the request: an identifier in {@code not_found} has none, and every other one takes the
 * next card.
 */
public record CardCollectionRecord(List<CardDto> cards,
                                   @JsonProperty("not_found") List<CardIdentifier> notFound) {
}
