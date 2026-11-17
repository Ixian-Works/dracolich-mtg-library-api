package dm.dracolich.mtgLibrary.dto.enums;

/**
 * How a card's colours, or its colour identity, are compared with the colours a search selects.
 * With white and blue selected:
 */
public enum ColorMatch {
    /** The card has every selected colour, and may have more: white-blue, and white-blue-black. */
    INCLUDES,
    /** The card has the selected colours and no other: white-blue only. */
    EXACTLY,
    /**
     * The card has no colour outside the selection: white, blue, white-blue and colourless. This
     * is the test a card has to pass to go in a Commander deck, against the commander's identity.
     */
    AT_MOST
}
