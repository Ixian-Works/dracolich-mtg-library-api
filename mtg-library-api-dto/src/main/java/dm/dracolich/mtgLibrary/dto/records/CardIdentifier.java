package dm.dracolich.mtgLibrary.dto.records;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One card to look up in a collection request: by its library id, or by its exact name, which may be
 * a face's name ("Fire" finds "Fire // Ice"). Give exactly one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardIdentifier(String id, String name) {

    public static CardIdentifier byId(String id) {
        return new CardIdentifier(id, null);
    }

    public static CardIdentifier byName(String name) {
        return new CardIdentifier(null, name);
    }
}
