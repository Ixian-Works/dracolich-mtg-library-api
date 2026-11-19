package dm.dracolich.mtgLibrary.web.seed;

/**
 * What one Scryfall sync wrote. {@code cardsGoneFromScryfall} counts the cards stored here that
 * Scryfall's file no longer has: they are kept, never deleted, and only counted.
 */
public record SyncCounts(long sets, long symbols, long cards, long rulings, long cardsGoneFromScryfall) { }
