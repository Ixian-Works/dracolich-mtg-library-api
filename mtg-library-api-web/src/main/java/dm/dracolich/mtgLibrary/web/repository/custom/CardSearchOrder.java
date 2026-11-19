package dm.dracolich.mtgLibrary.web.repository.custom;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static dm.dracolich.mtgLibrary.web.helpers.ErrorUtil.unknownSort;

/**
 * The order of a card search, and the aggregation stages that produce it.
 *
 * <p>A caller sorts with {@code sort=<key>[,asc|desc]}, repeatable. The keys are an allow-list:
 * anything else is a 400. Without a sort, a search with a name ranks the names that match best first,
 * then the most played. Without either, cards come in storage order. {@code _id} is always the last
 * key, so the order is total and paging never repeats or skips a card.</p>
 */
public final class CardSearchOrder {

    /** Present when the card has no EDHREC rank, so unranked cards sort after ranked ones. */
    static final int UNRANKED = Integer.MAX_VALUE;

    static final String RELEVANCE = "_relevance";
    static final String EDHREC = "_edhrec";

    /** Each key's stored fields, in the order they sort. */
    private static final Map<String, List<String>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("name", List.of("name"));
        KEYS.put("first_release_date", List.of("first_release_date"));
        KEYS.put("mana_value", List.of("default_face.gameplay_property.mana_value"));
        KEYS.put("edhrec_rank", List.of(EDHREC));
        // A set's cards as players know them: by the number inside the collector number, then the
        // number as text, so 12 < 12a < 13 (see CollectorNumbers).
        KEYS.put("collector_number", List.of("collector_order", "collector_number"));
    }

    private CardSearchOrder() {
    }

    /** The sort keys a caller may use, for messages and documentation. */
    public static String allowedKeys() {
        return String.join(", ", KEYS.keySet());
    }

    /**
     * Refuses {@code collector_number} without a {@code set_code} filter (400, DMD045): collector
     * numbers mean nothing across sets, and only within a set does an index serve that order.
     */
    static void requireSetForCollectorNumber(List<String> sortParams, String setCode) {
        if (setCode != null && !setCode.isBlank()) return;
        List<String> joined = SortParameters.joined(sortParams);
        if (joined == null) return;
        for (String param : joined) {
            String key = param == null ? "" : param.trim().split("\\s*,\\s*")[0].toLowerCase(Locale.ROOT);
            if (key.equals("collector_number")) {
                throw unknownSort(param, "name, first_release_date, mana_value or edhrec_rank;"
                        + " collector_number only with a set_code filter");
            }
        }
    }

    /** Turns the request's sort parameters into a {@code $sort} document. */
    static Document sort(List<String> sortParams, String name) {
        sortParams = SortParameters.joined(sortParams);
        Document sort = new Document();
        if (sortParams != null && !sortParams.isEmpty()) {
            for (String param : sortParams) {
                addKey(sort, param);
            }
        } else if (hasName(name)) {
            sort.append(RELEVANCE, 1).append(EDHREC, 1).append("name", 1);
        }
        sort.append("_id", 1);
        return sort;
    }

    private static void addKey(Document sort, String param) {
        String[] parts = param == null ? new String[0] : param.trim().split("\\s*,\\s*");
        if (parts.length == 0 || parts.length > 2 || !KEYS.containsKey(parts[0].toLowerCase(Locale.ROOT))) {
            throw unknownSort(param, allowedKeys());
        }
        int direction = 1;
        if (parts.length == 2) {
            direction = switch (parts[1].toLowerCase(Locale.ROOT)) {
                case "asc" -> 1;
                case "desc" -> -1;
                default -> throw unknownSort(param, allowedKeys());
            };
        }
        for (String field : KEYS.get(parts[0].toLowerCase(Locale.ROOT))) {
            sort.append(field, direction);
        }
    }

    /**
     * The {@code $addFields} stage the sort reads: the EDHREC rank with unranked cards last, and, for
     * a search with a name, how well the name matches. The typed text is compared as plain text,
     * never as a pattern.
     *
     * <ol start="0">
     *   <li>the whole name, ignoring case</li>
     *   <li>one whole word of the name ({@code bolt} in "Lightning Bolt")</li>
     *   <li>the start of the name</li>
     *   <li>the start of a word</li>
     *   <li>anywhere else</li>
     * </ol>
     */
    static Document addFields(String name) {
        Document fields = new Document(EDHREC, new Document("$ifNull", List.of("$edhrec_rank", UNRANKED)));
        if (hasName(name)) {
            String text = name.trim().toLowerCase(Locale.ROOT);
            Document lowerName = new Document("$toLower", "$name");
            Document words = new Document("$split", List.of(lowerName, " "));
            Document startsWithText = new Document("$eq", List.of(new Document("$indexOfCP", List.of("$$word", text)), 0));
            List<Document> branches = new ArrayList<>();
            branches.add(branch(new Document("$eq", List.of(lowerName, text)), 0));
            branches.add(branch(new Document("$in", List.of(text, words)), 1));
            branches.add(branch(new Document("$eq", List.of(new Document("$indexOfCP", List.of(lowerName, text)), 0)), 2));
            branches.add(branch(new Document("$anyElementTrue", List.of(new Document("$map",
                    new Document("input", words).append("as", "word").append("in", startsWithText)))), 3));
            fields.append(RELEVANCE, new Document("$switch",
                    new Document("branches", branches).append("default", 4)));
        }
        return fields;
    }

    /** The whole pipeline for one page, from the already mapped {@code $match} document. */
    static List<Document> pipeline(Document match, String name, List<String> sortParams, int page, int size) {
        Document sort = sort(sortParams, name);
        // Only the helper fields the sort reads are added. A sort on stored fields alone, such as the
        // newest-first view, then reaches $sort directly and can be served by an index.
        Document fields = new Document();
        addFields(name).forEach((field, expression) -> {
            if (sort.containsKey(field)) fields.append(field, expression);
        });
        List<Document> stages = new ArrayList<>();
        stages.add(new Document("$match", match));
        if (!fields.isEmpty()) stages.add(new Document("$addFields", fields));
        stages.add(new Document("$sort", sort));
        stages.add(new Document("$skip", (long) page * size));
        stages.add(new Document("$limit", size));
        if (!fields.isEmpty()) stages.add(new Document("$project", fields.keySet().stream()
                .collect(Collectors.toMap(k -> k, k -> 0, (a, b) -> a, Document::new))));
        return stages;
    }

    private static Document branch(Document condition, int rank) {
        return new Document("case", condition).append("then", rank);
    }

    private static boolean hasName(String name) {
        return name != null && !name.isBlank();
    }
}
