package dev.jaoow.cotatrack.api.requests.historicalquotes.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents the interval between each quote.
 * I.g: to fetch the historical quotes of a stock for the last 5 days with a 1-minute interval, you would use the FIVE_DAYS range and ONE_MINUTE interval.
 */
@Getter
@RequiredArgsConstructor
public enum Interval {

    ONE_MINUTE("1m"),
    TWO_MINUTES("2m"),
    FIVE_MINUTES("5m"),
    FIFTEEN_MINUTES("15m"),
    THIRTY_MINUTES("30m"),
    SIXTY_MINUTES("60m"),
    NINETY_MINUTES("90m"),
    ONE_HOUR("1h"),
    ONE_DAY("1d"),
    FIVE_DAYS("5d"),
    ONE_WEEK("1wk"),
    ONE_MONTH("1mo"),
    THREE_MONTHS("3mo");

    private final String tag;

    public static Interval fromTag(String tag) {
        for (Interval interval : values()) {
            if (interval.getTag().equals(tag)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unknown tag: " + tag);
    }

    public static String getValidTags() {
        StringBuilder validTags = new StringBuilder();
        for (Interval interval : values()) {
            validTags.append(interval.getTag()).append(", ");
        }
        return validTags.substring(0, validTags.length() - 2); // Remove the trailing comma and space
    }
}
