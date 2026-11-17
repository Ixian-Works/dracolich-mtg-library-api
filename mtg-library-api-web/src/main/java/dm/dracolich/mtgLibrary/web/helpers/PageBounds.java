package dm.dracolich.mtgLibrary.web.helpers;

import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.mtgLibrary.dto.error.ErrorCodes;
import org.springframework.http.HttpStatus;

import java.util.List;

/** The page a paged endpoint may be asked for. */
public final class PageBounds {

    public static final String DEFAULT_PAGE = "0";
    public static final String DEFAULT_SIZE = "20";
    public static final int MAX_SIZE = 100;

    private PageBounds() {
    }

    /** Throws a 400 unless {@code page >= 0} and {@code 1 <= size <= 100}. */
    public static void check(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            var error = new ApiError(ErrorCodes.DMD046);
            throw new ResponseException(ErrorCodes.DMD046.format(String.valueOf(page), String.valueOf(size)),
                    List.of(error), HttpStatus.BAD_REQUEST);
        }
    }
}
