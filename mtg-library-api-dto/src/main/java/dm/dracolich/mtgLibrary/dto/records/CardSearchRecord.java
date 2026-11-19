package dm.dracolich.mtgLibrary.dto.records;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.mtgLibrary.dto.enums.CardType;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.dto.enums.ColorMatch;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Rarity;

import java.util.Set;

// All filters optional. Empty record returns all cards, paginated.
//
// colors and color_identity each have a match mode beside them. Without one the mode is INCLUDES,
// which is what both filters did before the modes existed. With EXACTLY or AT_MOST an empty set
// is a filter too, and means colourless.
public record CardSearchRecord(
        @JsonProperty("oracle_text") String oracleText,
        Set<Color> colors,
        @JsonProperty("color_identity") Set<Color> colorIdentity,
        Set<CardType> types,
        Set<String> subtypes,
        Set<String> keywords,
        @JsonProperty("legal_in") Format legalIn,
        Rarity rarity,
        @JsonProperty("min_mana_value") Double minManaValue,
        @JsonProperty("max_mana_value") Double maxManaValue,
        @JsonProperty("set_code") String setCode,
        @JsonProperty("colors_match") ColorMatch colorsMatch,
        @JsonProperty("color_identity_match") ColorMatch colorIdentityMatch,
        // true: only cards that can be a Commander deck's commander.
        @JsonProperty("can_be_commander") Boolean canBeCommander,
        // A card's id: only the cards that may be chosen beside it as the deck's second commander.
        @JsonProperty("beside_commander") String besideCommander
) {
    /** The record as it was before the commander filters, so callers written against it still compile. */
    public CardSearchRecord(String oracleText, Set<Color> colors, Set<Color> colorIdentity, Set<CardType> types,
                            Set<String> subtypes, Set<String> keywords, Format legalIn, Rarity rarity,
                            Double minManaValue, Double maxManaValue, String setCode,
                            ColorMatch colorsMatch, ColorMatch colorIdentityMatch) {
        this(oracleText, colors, colorIdentity, types, subtypes, keywords, legalIn, rarity,
                minManaValue, maxManaValue, setCode, colorsMatch, colorIdentityMatch, null, null);
    }

    /** The record as it was before the match modes, so callers written against it still compile. */
    public CardSearchRecord(String oracleText, Set<Color> colors, Set<Color> colorIdentity, Set<CardType> types,
                            Set<String> subtypes, Set<String> keywords, Format legalIn, Rarity rarity,
                            Double minManaValue, Double maxManaValue, String setCode) {
        this(oracleText, colors, colorIdentity, types, subtypes, keywords, legalIn, rarity,
                minManaValue, maxManaValue, setCode, null, null, null, null);
    }
}
