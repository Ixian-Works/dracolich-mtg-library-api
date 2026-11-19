package dm.dracolich.mtgLibrary.web.repository.custom;

/**
 * How collector numbers sort. They aren't plain numbers: {@code 12}, {@code 12a}, {@code ★12},
 * {@code A-12}, {@code 12★}. A card is ordered by the first run of digits in its number, then by the
 * number as text, so {@code 12} < {@code 12a} < {@code A-12} < {@code ★12} < {@code 13}. A number with
 * no digits goes after every numbered card.
 */
public final class CollectorNumbers {

    /** The order of a number with no digits, or of a card with no number: after every numbered card. */
    public static final int NO_NUMBER = Integer.MAX_VALUE;

    private CollectorNumbers() {
    }

    /** The number inside a collector number: its first run of digits, or {@link #NO_NUMBER}. */
    public static int order(String collectorNumber) {
        if (collectorNumber == null) return NO_NUMBER;
        int start = -1;
        for (int i = 0; i < collectorNumber.length(); i++) {
            if (Character.isDigit(collectorNumber.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) return NO_NUMBER;
        long value = 0;
        int i = start;
        while (i < collectorNumber.length() && Character.isDigit(collectorNumber.charAt(i))) {
            value = value * 10 + Character.digit(collectorNumber.charAt(i), 10);
            if (value >= NO_NUMBER) return NO_NUMBER - 1;
            i++;
        }
        return (int) value;
    }
}
