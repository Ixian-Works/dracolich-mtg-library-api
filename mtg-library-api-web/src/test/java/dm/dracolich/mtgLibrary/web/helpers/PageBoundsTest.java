package dm.dracolich.mtgLibrary.web.helpers;

import dm.dracolich.forge.exception.ResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class PageBoundsTest {

    @Test
    void theEdgesAreAllowed() {
        assertThatCode(() -> PageBounds.check(0, 1)).doesNotThrowAnyException();
        assertThatCode(() -> PageBounds.check(500, 100)).doesNotThrowAnyException();
    }

    @Test
    void outsideTheEdgesIs400() {
        for (int[] bad : new int[][]{{-1, 20}, {0, 0}, {0, 101}, {0, -5}}) {
            assertThatThrownBy(() -> PageBounds.check(bad[0], bad[1]))
                    .isInstanceOfSatisfying(ResponseException.class, e -> {
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getErrors()).extracting("error").containsExactly("DMD046");
                    });
        }
    }
}
