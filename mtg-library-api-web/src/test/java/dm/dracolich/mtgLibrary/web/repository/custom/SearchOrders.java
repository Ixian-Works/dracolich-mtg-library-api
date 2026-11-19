package dm.dracolich.mtgLibrary.web.repository.custom;

import org.bson.Document;
import org.springframework.data.domain.Sort;

import java.util.List;

/** The two searches' orders, for tests outside this package. */
public final class SearchOrders {

    private SearchOrders() {
    }

    public static Document cardSort(List<String> sortParams) {
        return CardSearchOrder.sort(sortParams, null);
    }

    public static Sort setSort(List<String> sortParams) {
        return SetSearchOrder.sort(sortParams);
    }
}
