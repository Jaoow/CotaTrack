package dev.jaoow.cotatrack.api.requests.historicalquotes.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents the range of the historical quotes.
 * I.g: to fetch the historical quotes of a stock for the last 5 days, you would use the FIVE_DAYS range.
 */
@Getter
@RequiredArgsConstructor
public enum Range {

    ONE_DAY("1d"),
    FIVE_DAYS("5d"),
    ONE_MONTH("1mo"),
    THREE_MONTHS("3mo"),
    SIX_MONTHS("6mo"),
    ONE_YEAR("1y"),
    TWO_YEARS("2y"),
    FIVE_YEARS("5y"),
    TEN_YEARS("10y"),
    YEAR_TO_DATE("ytd"),
    MAX("max");

    private final String tag;

    public static Range fromTag(String tag) {
        for (Range range : values()) {
            if (range.getTag().equals(tag)) {
                return range;
            }
        }
        throw new IllegalArgumentException("Unknown tag: " + tag);
    }

    public static String getValidTags() {
        StringBuilder validTags = new StringBuilder();
        for (Range range : values()) {
            validTags.append(range.getTag()).append(", ");
        }
        return validTags.substring(0, validTags.length() - 2); // Remove the trailing comma and space
    }

    @Override
    public String toString() {
        return tag;
    }
}
