package dm.dracolich.mtgLibrary.web.seed;

import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Legality;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.entity.CardFaceEntity;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a card can be a Commander deck's commander, and what it can be paired with, worked out from
 * the card at import (Ixian-Works/dracolich-mtg-library-api#22).
 *
 * <p><b>Can be a commander:</b> the front face is a legendary creature, or its rules text says it
 * "can be your commander"; and the card isn't banned or not legal in Commander. Not covered yet:
 * legendary Vehicles and Spacecraft, which the current rules don't make commanders by type alone,
 * and the Oathbreaker and Brawl variants.</p>
 *
 * <p><b>Pairings</b>, each a token in {@code pairings}:</p>
 * <ul>
 *   <li>{@code partner}: plain Partner, pairs with any other plain Partner.</li>
 *   <li>{@code partner_with:<name>}: Partner with that one card.</li>
 *   <li>{@code partner_group:<name>}: a named Partner, such as "Partner—Survivors", pairs within its name.</li>
 *   <li>{@code friends_forever}: pairs with any other Friends forever.</li>
 *   <li>{@code choose_a_background}: pairs with a Background.</li>
 *   <li>{@code background}: a legendary Background enchantment, chosen beside a commander that says
 *       "choose a Background". It is never a commander itself.</li>
 *   <li>{@code doctors_companion}: pairs with a Time Lord Doctor.</li>
 *   <li>{@code doctor}: a Time Lord Doctor that can be a commander, chosen beside a Doctor's companion.</li>
 * </ul>
 */
public final class CommanderRules {

    public static final String PARTNER = "partner";
    public static final String PARTNER_WITH = "partner_with:";
    public static final String PARTNER_GROUP = "partner_group:";
    public static final String FRIENDS_FOREVER = "friends_forever";
    public static final String CHOOSE_A_BACKGROUND = "choose_a_background";
    public static final String BACKGROUND = "background";
    public static final String DOCTORS_COMPANION = "doctors_companion";
    public static final String DOCTOR = "doctor";

    // Read line by line from the front face's rules text. Each stops at a reminder in brackets.
    private static final Pattern PARTNER_WITH_LINE = Pattern.compile("^Partner with ([^(\\n]+?)\\s*(?:\\(|$)", Pattern.MULTILINE);
    private static final Pattern PARTNER_GROUP_LINE = Pattern.compile("^Partner—([^(\\n]+?)\\s*(?:\\(|$)", Pattern.MULTILINE);
    private static final Pattern PLAIN_PARTNER_LINE = Pattern.compile("^Partner\\s*(?:\\(|$)", Pattern.MULTILINE);

    private CommanderRules() {
    }

    /** What the import stores: whether the card can be a commander, and its pairings. */
    public record Result(boolean canBeCommander, Set<String> pairings) { }

    public static Result of(CardEntity card) {
        CardFaceEntity front = card.getDefaultFace();
        String type = front != null && front.getFullType() != null ? front.getFullType() : "";
        String text = front != null && front.getOracleText() != null ? front.getOracleText() : "";
        String typeLeft = type.split("—", 2)[0];
        String typeRight = type.contains("—") ? type.split("—", 2)[1] : "";
        Set<String> keywords = lower(card.getKeywords());

        boolean legendaryCreature = hasWord(typeLeft, "Legendary") && hasWord(typeLeft, "Creature");
        boolean saysSo = text.toLowerCase(Locale.ROOT).contains("can be your commander");
        boolean legal = isLegal(card.getLegalities());
        boolean canBeCommander = legal && (legendaryCreature || saysSo);
        boolean background = legal && hasWord(typeLeft, "Legendary") && hasWord(typeLeft, "Enchantment")
                && hasWord(typeRight, "Background");

        Set<String> pairings = new TreeSet<>();
        if (canBeCommander) {
            Matcher with = PARTNER_WITH_LINE.matcher(text);
            Matcher group = PARTNER_GROUP_LINE.matcher(text);
            if (with.find()) {
                pairings.add(PARTNER_WITH + with.group(1).trim().toLowerCase(Locale.ROOT));
            } else if (group.find()) {
                pairings.add(PARTNER_GROUP + group.group(1).trim().toLowerCase(Locale.ROOT));
            } else if (keywords.contains("partner") || PLAIN_PARTNER_LINE.matcher(text).find()) {
                pairings.add(PARTNER);
            }
            if (keywords.contains("friends forever")) pairings.add(FRIENDS_FOREVER);
            if (keywords.contains("choose a background")) pairings.add(CHOOSE_A_BACKGROUND);
            if (keywords.contains("doctor's companion")) pairings.add(DOCTORS_COMPANION);
            if (hasWord(typeRight, "Time") && hasWord(typeRight, "Lord") && hasWord(typeRight, "Doctor")) {
                pairings.add(DOCTOR);
            }
        }
        if (background) pairings.add(BACKGROUND);
        return new Result(canBeCommander, pairings);
    }

    /** Legal in Commander, or no word on it; banned or not legal is not. */
    private static boolean isLegal(Map<Format, Legality> legalities) {
        if (legalities == null) return true;
        Legality commander = legalities.get(Format.COMMANDER);
        return commander == null || commander == Legality.LEGAL;
    }

    private static boolean hasWord(String text, String word) {
        for (String part : text.trim().split("\\s+")) {
            if (part.equalsIgnoreCase(word)) return true;
        }
        return false;
    }

    private static Set<String> lower(Set<String> keywords) {
        Set<String> out = new TreeSet<>();
        if (keywords != null) keywords.forEach(k -> out.add(k.toLowerCase(Locale.ROOT)));
        return out;
    }
}
