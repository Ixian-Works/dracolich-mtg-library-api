package dm.dracolich.mtgLibrary.web.repository.custom;

import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dm.dracolich.mtgLibrary.web.helpers.ErrorUtil.unknownSort;

/**
 * The order of a set search. {@code sort=release_date} (newest first unless {@code ,asc}) is the
 * default; {@code sort=name} is A to Z unless {@code ,desc}. Anything else is a 400 (DMD045). A set
 * with no release date comes last newest first, as MongoDB orders a missing value lowest. {@code _id}
 * ends every order, so paging never repeats or skips a set.
 */
public final class SetSearchOrder {

    private static final Map<String, Sort.Direction> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("release_date", Sort.Direction.DESC);
        KEYS.put("name", Sort.Direction.ASC);
    }

    private SetSearchOrder() {
    }

    public static String allowedKeys() {
        return String.join(", ", KEYS.keySet());
    }

    /** The sort for the request's {@code sort} parameter, release date newest first when there is none. */
    public static Sort sort(List<String> sortParams) {
        Sort sort = Sort.unsorted();
        if (sortParams == null || sortParams.isEmpty()) {
            sort = Sort.by(Sort.Direction.DESC, "release_date");
        } else {
            for (String param : sortParams) {
                sort = sort.and(key(param));
            }
        }
        return sort.and(Sort.by(Sort.Direction.ASC, "_id"));
    }

    private static Sort key(String param) {
        String[] parts = param == null ? new String[0] : param.trim().split("\\s*,\\s*");
        if (parts.length == 0 || parts.length > 2 || !KEYS.containsKey(parts[0].toLowerCase(Locale.ROOT))) {
            throw unknownSort(param, allowedKeys());
        }
        String field = parts[0].toLowerCase(Locale.ROOT);
        Sort.Direction direction = KEYS.get(field);
        if (parts.length == 2) {
            direction = switch (parts[1].toLowerCase(Locale.ROOT)) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw unknownSort(param, allowedKeys());
            };
        }
        return Sort.by(direction, field);
    }
}
