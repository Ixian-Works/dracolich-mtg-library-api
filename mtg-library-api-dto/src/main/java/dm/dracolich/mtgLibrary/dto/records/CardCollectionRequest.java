package dm.dracolich.mtgLibrary.dto.records;

import java.util.List;

/** Up to {@link #MAX_IDENTIFIERS} cards to look up in one request. */
public record CardCollectionRequest(List<CardIdentifier> identifiers) {

    public static final int MAX_IDENTIFIERS = 100;
}
