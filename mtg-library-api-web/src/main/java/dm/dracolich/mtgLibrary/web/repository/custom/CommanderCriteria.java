package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.BACKGROUND;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.CHOOSE_A_BACKGROUND;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.DOCTOR;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.DOCTORS_COMPANION;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.FRIENDS_FOREVER;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.PARTNER;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.PARTNER_GROUP;
import static dm.dracolich.mtgLibrary.web.seed.CommanderRules.PARTNER_WITH;

/** Which cards may be chosen beside a commander, from that commander's pairings (see CommanderRules). */
final class CommanderCriteria {

    private CommanderCriteria() {
    }

    /** The cards allowed beside {@code first}; none when it has no pairing. Never {@code first} itself. */
    static Criteria beside(CardEntity first) {
        Set<String> pairings = first.getPairings() != null ? first.getPairings() : Set.of();
        List<Criteria> allowed = new ArrayList<>();
        for (String pairing : pairings) {
            if (pairing.equals(PARTNER)) {
                allowed.add(commanderWith(PARTNER));
            } else if (pairing.startsWith(PARTNER_GROUP)) {
                allowed.add(commanderWith(pairing));
            } else if (pairing.equals(FRIENDS_FOREVER)) {
                allowed.add(commanderWith(FRIENDS_FOREVER));
            } else if (pairing.equals(CHOOSE_A_BACKGROUND)) {
                allowed.add(Criteria.where("pairings").is(BACKGROUND));
            } else if (pairing.equals(BACKGROUND)) {
                allowed.add(commanderWith(CHOOSE_A_BACKGROUND));
            } else if (pairing.equals(DOCTORS_COMPANION)) {
                allowed.add(commanderWith(DOCTOR));
            } else if (pairing.equals(DOCTOR)) {
                allowed.add(commanderWith(DOCTORS_COMPANION));
            } else if (pairing.startsWith(PARTNER_WITH)) {
                String partner = pairing.substring(PARTNER_WITH.length()).toLowerCase(Locale.ROOT);
                allowed.add(Criteria.where("can_be_commander").is(true).and(CardEntity.NAME_KEYS).is(partner));
            }
        }
        if (allowed.isEmpty()) {
            // No pairing: nothing may be chosen beside it.
            return Criteria.where("_id").in(List.of());
        }
        return new Criteria().andOperator(
                Criteria.where("_id").ne(first.getId()),
                new Criteria().orOperator(allowed.toArray(Criteria[]::new)));
    }

    private static Criteria commanderWith(String pairing) {
        return Criteria.where("can_be_commander").is(true).and("pairings").is(pairing);
    }
}
