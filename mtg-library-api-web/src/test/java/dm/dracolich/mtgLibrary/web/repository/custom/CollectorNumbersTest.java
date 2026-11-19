package dm.dracolich.mtgLibrary.web.repository.custom;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorNumbersTest {

    @ParameterizedTest
    @CsvSource({"12,12", "12a,12", "★12,12", "A-12,12", "12★,12", "0,0", "007,7", "1000s,1000"})
    void theNumberInsideIsTheFirstRunOfDigits(String collectorNumber, int order) {
        assertThat(CollectorNumbers.order(collectorNumber)).isEqualTo(order);
    }

    @Test
    void noDigitsOrNoNumberGoesLast() {
        assertThat(CollectorNumbers.order("★")).isEqualTo(CollectorNumbers.NO_NUMBER);
        assertThat(CollectorNumbers.order("")).isEqualTo(CollectorNumbers.NO_NUMBER);
        assertThat(CollectorNumbers.order(null)).isEqualTo(CollectorNumbers.NO_NUMBER);
    }

    @Test
    void aNumberTooBigForAnIntStillSortsBeforeNoNumber() {
        assertThat(CollectorNumbers.order("99999999999")).isLessThan(CollectorNumbers.NO_NUMBER);
    }
}
