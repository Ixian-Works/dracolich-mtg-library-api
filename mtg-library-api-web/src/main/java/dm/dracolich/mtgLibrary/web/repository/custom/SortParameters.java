package dm.dracolich.mtgLibrary.web.repository.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code sort} parameter as a search reads it. Spring binds a single {@code sort=key,asc} to a
 * list by splitting it at the comma, into {@code ["key", "asc"]}, while two parameters stay whole,
 * {@code ["key,asc", "name"]}. A lone {@code asc} or {@code desc} is therefore the direction of the
 * key before it, and is joined back to it here, so both searches read every form the same way.
 */
final class SortParameters {

    private SortParameters() {
    }

    static List<String> joined(List<String> params) {
        if (params == null) return null;
        List<String> joined = new ArrayList<>(params.size());
        for (String param : params) {
            String trimmed = param == null ? "" : param.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            boolean direction = lower.equals("asc") || lower.equals("desc");
            if (direction && !joined.isEmpty() && !joined.getLast().contains(",")) {
                joined.set(joined.size() - 1, joined.getLast() + "," + trimmed);
            } else {
                joined.add(param);
            }
        }
        return joined;
    }
}
