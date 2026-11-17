package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.dto.enums.ColorMatch;
import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import org.bson.Document;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MongoDB query a colour filter becomes, for each match mode. Nothing here runs the query:
 * these tests fix the document that is sent, and the comments say what MongoDB does with it.
 */
class CardSearchColourCriteriaTest {

    private static final String IDENTITY = "default_face.gameplay_property.color_identity";
    private static final String FLIPPED_IDENTITY = "flipped_face.gameplay_property.color_identity";
    private static final String COLORS = "default_face.gameplay_property.colors";
    private static final String FLIPPED_COLORS = "flipped_face.gameplay_property.colors";

    @Nested
    class ColourIdentity {

        @Test
        void includesIsTheQueryItHasAlwaysBeen() {
            Document expected = filters("""
                    {"$or": [{"%s": {"$all": ["W", "U"]}}, {"%s": {"$all": ["W", "U"]}}]}
                    """.formatted(IDENTITY, FLIPPED_IDENTITY));

            assertThat(query(identity(ColorMatch.INCLUDES, Color.W, Color.U))).isEqualTo(expected);
            // No mode is INCLUDES, so a caller that knows nothing of modes gets what it got before.
            assertThat(query(identity(null, Color.W, Color.U))).isEqualTo(expected);
        }

        @Test
        void exactlyIsEveryColourAndNoMore() {
            // $all: every selected colour is there. $size: nothing else is.
            assertThat(query(identity(ColorMatch.EXACTLY, Color.W, Color.U))).isEqualTo(filters("""
                    {"%s": {"$all": ["W", "U"], "$size": 2}}
                    """.formatted(IDENTITY)));
        }

        @Test
        void atMostIsNoColourOutsideTheSelectionOnACardThatHasAnIdentityStored() {
            // $elemMatch + $nin finds a colour outside the selection, and $not turns that down.
            // A card with an empty list has no such colour, so colourless passes. $exists keeps
            // out a card with no list at all, which would otherwise pass every search.
            assertThat(query(identity(ColorMatch.AT_MOST, Color.W, Color.U))).isEqualTo(filters("""
                    {"%s": {"$exists": true, "$not": {"$elemMatch": {"$nin": ["W", "U"]}}}}
                    """.formatted(IDENTITY)));
        }

        @Test
        void anEmptySelectionMeansColourlessInTheTwoNewModes() {
            // $all with an empty list matches nothing, so EXACTLY uses the size alone.
            assertThat(query(identity(ColorMatch.EXACTLY))).isEqualTo(filters("""
                    {"%s": {"$size": 0}}
                    """.formatted(IDENTITY)));
            // Every colour is outside an empty selection, so only an empty list passes.
            assertThat(query(identity(ColorMatch.AT_MOST))).isEqualTo(filters("""
                    {"%s": {"$exists": true, "$not": {"$elemMatch": {"$nin": []}}}}
                    """.formatted(IDENTITY)));
        }

        @Test
        void colourlessCanBeAskedForByName() {
            // C is not stored on any card: a colourless card has an empty list.
            assertThat(query(identity(ColorMatch.EXACTLY, Color.C)))
                    .isEqualTo(query(identity(ColorMatch.EXACTLY)));
            assertThat(query(identity(ColorMatch.AT_MOST, Color.C, Color.G)))
                    .isEqualTo(query(identity(ColorMatch.AT_MOST, Color.G)));
        }

        @Test
        void noSelectionIsNoFilterWhateverTheMode() {
            for (ColorMatch match : ColorMatch.values()) {
                CardSearchRecord search = new CardSearchRecord(null, null, null, null, null, null, null, null,
                        null, null, null, match, match);
                assertThat(query(search)).isEqualTo(new Document());
            }
            // An empty selection is no filter either with INCLUDES, as it has always been.
            assertThat(query(identity(ColorMatch.INCLUDES))).isEqualTo(new Document());
            assertThat(query(identity(null))).isEqualTo(new Document());
        }
    }

    @Nested
    class Colours {

        @Test
        void includesIsTheQueryItHasAlwaysBeen() {
            Document expected = filters("""
                    {"$or": [{"%s": {"$all": ["G"]}}, {"%s": {"$all": ["G"]}}]}
                    """.formatted(COLORS, FLIPPED_COLORS));

            assertThat(query(colors(ColorMatch.INCLUDES, Color.G))).isEqualTo(expected);
            assertThat(query(colors(null, Color.G))).isEqualTo(expected);
        }

        @Test
        void exactlyMatchesACardByEitherOfItsFaces() {
            assertThat(query(colors(ColorMatch.EXACTLY, Color.G))).isEqualTo(filters("""
                    {"$or": [{"%s": {"$all": ["G"], "$size": 1}}, {"%s": {"$all": ["G"], "$size": 1}}]}
                    """.formatted(COLORS, FLIPPED_COLORS)));
        }

        @Test
        void atMostNeedsBothFacesOfATwoFacedCardInsideTheSelection() {
            // A white front with a black back is not "at most white". The flipped face has no
            // $exists: a card with one face has no flipped face, and that must not rule it out.
            assertThat(query(colors(ColorMatch.AT_MOST, Color.W))).isEqualTo(filters("""
                    {"$and": [
                      {"%s": {"$exists": true, "$not": {"$elemMatch": {"$nin": ["W"]}}}},
                      {"%s": {"$not": {"$elemMatch": {"$nin": ["W"]}}}}]}
                    """.formatted(COLORS, FLIPPED_COLORS)));
        }
    }

    @Test
    void theTwoFiltersAndTheirModesAreIndependent() {
        CardSearchRecord search = new CardSearchRecord(null, ordered(Color.R), ordered(Color.W, Color.U), null,
                null, null, null, null, null, null, null, ColorMatch.EXACTLY, ColorMatch.AT_MOST);

        assertThat(query(search)).isEqualTo(Document.parse("""
                {"$and": [
                  {"%s": {"$exists": true, "$not": {"$elemMatch": {"$nin": ["W", "U"]}}}},
                  {"$or": [{"%s": {"$all": ["R"], "$size": 1}}, {"%s": {"$all": ["R"], "$size": 1}}]}]}
                """.formatted(IDENTITY, COLORS, FLIPPED_COLORS)));
    }

    @Nested
    class TheRequestBody {

        private final JsonMapper json = JsonMapper.builder().build();

        @Test
        void aBodyWithNoModesReadsAsItAlwaysDid() {
            CardSearchRecord search = json.readValue("""
                    {"color_identity": ["W", "U"], "colors": ["G"]}
                    """, CardSearchRecord.class);

            assertThat(search.colorIdentity()).containsExactly(Color.W, Color.U);
            assertThat(search.colorIdentityMatch()).isNull();
            assertThat(search.colorsMatch()).isNull();
        }

        @Test
        void theModesAreReadFromSnakeCaseFields() {
            CardSearchRecord search = json.readValue("""
                    {"color_identity": ["W", "U"], "color_identity_match": "AT_MOST",
                     "colors": [], "colors_match": "EXACTLY"}
                    """, CardSearchRecord.class);

            assertThat(search.colorIdentityMatch()).isEqualTo(ColorMatch.AT_MOST);
            assertThat(search.colorsMatch()).isEqualTo(ColorMatch.EXACTLY);
            assertThat(search.colors()).isEmpty();
        }

        @Test
        void codeWrittenBeforeTheModesStillCompilesAndMeansIncludes() {
            CardSearchRecord search = new CardSearchRecord(null, null, Set.of(Color.W), null, null, null, null,
                    null, null, null, null);

            assertThat(search.colorIdentityMatch()).isNull();
            assertThat(search.colorsMatch()).isNull();
        }
    }

    // ---------- Helpers ----------

    private static Document query(CardSearchRecord search) {
        return CardCustomRepositoryImpl.buildCriteria(null, search).getCriteriaObject();
    }

    /** One filter, as buildCriteria wraps it. */
    private static Document filters(String filter) {
        return new Document("$and", List.of(Document.parse(filter)));
    }

    private static CardSearchRecord identity(ColorMatch match, Color... colors) {
        return new CardSearchRecord(null, null, ordered(colors), null, null, null, null, null, null, null, null,
                null, match);
    }

    private static CardSearchRecord colors(ColorMatch match, Color... colors) {
        return new CardSearchRecord(null, ordered(colors), null, null, null, null, null, null, null, null, null,
                match, null);
    }

    private static Set<Color> ordered(Color... colors) {
        return new LinkedHashSet<>(List.of(colors));
    }
}
