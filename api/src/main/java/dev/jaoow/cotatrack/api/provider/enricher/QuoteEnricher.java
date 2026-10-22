package dev.jaoow.cotatrack.api.provider.enricher;

import dev.jaoow.cotatrack.api.classification.AssetClassification;
import dev.jaoow.cotatrack.api.classification.AssetClassifier;
import dev.jaoow.cotatrack.api.classification.AssetMarket;
import dev.jaoow.cotatrack.api.requests.dividends.model.CashDividend;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Utility to calculate derived and missing fields in {@link Quote} objects.
 * When primary or fallback providers do not supply specific metrics, this enricher
 * complements them using mathematical relationships or historical candle data.
 *
 * <p>Derived fields include:</p>
 * <ul>
 *     <li>{@code regularMarketDayRange}: formatted string "low - high"</li>
 *     <li>{@code fiftyTwoWeekRange}: formatted string "52wLow - 52wHigh"</li>
 *     <li>{@code fiftyTwoWeekHighChange}: {@code price - fiftyTwoWeekHigh}</li>
 *     <li>{@code fiftyTwoWeekHighChangePercent}: {@code (price - fiftyTwoWeekHigh) / fiftyTwoWeekHigh}</li>
 *     <li>{@code fiftyTwoWeekLowChange}: {@code price - fiftyTwoWeekLow}</li>
 *     <li>{@code twoHundredDayAverage}: 200-session SMA calculated from daily closes</li>
 *     <li>{@code twoHundredDayAverageChange}: {@code price - twoHundredDayAverage}</li>
 *     <li>{@code twoHundredDayAverageChangePercent}: {@code (price - twoHundredDayAverage) / twoHundredDayAverage}</li>
 *     <li>{@code averageDailyVolume10Day}: 10-session mean trading volume</li>
 *     <li>{@code averageDailyVolume3Month}: 63-session mean trading volume (~3 calendar months)</li>
 *     <li>{@code regularMarketPreviousClose}: {@code price - regularMarketChange}</li>
 *     <li>{@code dividendRate} / {@code dividendYield}: mutual derivation and B3 proxy trailing-12m aggregation</li>
 * </ul>
 */
@Slf4j
public class QuoteEnricher {

    private static final DateTimeFormatter B3_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Enriches a single {@link Quote} with all derived intra-quote values.
     *
     * @param quote the quote to enrich
     * @return the enriched quote
     */
    public static Quote enrich(Quote quote) {
        return enrich(quote, AssetMarket.B3);
    }

    public static Quote enrich(Quote quote, AssetMarket market) {
        if (quote == null) {
            return null;
        }

        // Ensure asset classification is populated
        if (market == AssetMarket.US && quote.getCategory() == null) {
            quote.setCategory("EQUITIES");
            quote.setType("stock");
            if (quote.getSubType() == null) quote.setSubType("common");
        }
        if (market == AssetMarket.B3 && quote.getCategory() == null) {
            AssetClassification classification = AssetClassifier.classify(
                    quote.getSymbol(),
                    quote.getType(),
                    quote.getSubType(),
                    quote.getSector(),
                    quote.getIndustry(),
                    quote.getLongName() != null ? quote.getLongName() : quote.getShortName());

            quote.setCategory(classification.getCategoryName());
            if (quote.getType() == null) quote.setType(classification.getType());
            if (quote.getSubType() == null) quote.setSubType(classification.getSubType());
            if (quote.getSector() == null) quote.setSector(classification.getSector());
            if (quote.getIndustry() == null) quote.setIndustry(classification.getIndustry());
        }

        Double price = quote.getRegularMarketPrice();
        Double change = quote.getRegularMarketChange();
        Double prevClose = quote.getRegularMarketPreviousClose();
        Double dayHigh = quote.getRegularMarketDayHigh();
        Double dayLow = quote.getRegularMarketDayLow();

        // 1. Day High & Low fallbacks to price if completely absent
        if (market == AssetMarket.B3 && dayHigh == null && price != null) {
            quote.setRegularMarketDayHigh(price);
            dayHigh = price;
        }
        if (market == AssetMarket.B3 && dayLow == null && price != null) {
            quote.setRegularMarketDayLow(price);
            dayLow = price;
        }

        // 2. Day Range (e.g., "58.02 - 59.30")
        if (quote.getRegularMarketDayRange() == null && dayLow != null && dayHigh != null) {
            quote.setRegularMarketDayRange(formatRange(dayLow, dayHigh));
        }

        // 3. Previous close / price change relationships
        if (prevClose == null && price != null && change != null) {
            prevClose = round(price - change, 4);
            quote.setRegularMarketPreviousClose(prevClose);
        }
        if (change == null && price != null && prevClose != null) {
            change = round(price - prevClose, 4);
            quote.setRegularMarketChange(change);
        }
        if (quote.getRegularMarketChangePercent() == null && change != null && prevClose != null && prevClose > 0) {
            quote.setRegularMarketChangePercent(round((change / prevClose) * 100.0, 4));
        }

        // 4. 52-Week Range & Variations
        Double high52 = quote.getFiftyTwoWeekHigh();
        Double low52 = quote.getFiftyTwoWeekLow();
        if (quote.getFiftyTwoWeekRange() == null && low52 != null && high52 != null) {
            quote.setFiftyTwoWeekRange(formatRange(low52, high52));
        }
        if (quote.getFiftyTwoWeekHighChange() == null && price != null && high52 != null) {
            quote.setFiftyTwoWeekHighChange(round(price - high52, 4));
        }
        if (quote.getFiftyTwoWeekHighChangePercent() == null && price != null && high52 != null && high52 > 0) {
            quote.setFiftyTwoWeekHighChangePercent(round((price - high52) / high52, 6));
        }
        if (quote.getFiftyTwoWeekLowChange() == null && price != null && low52 != null) {
            quote.setFiftyTwoWeekLowChange(round(price - low52, 4));
        }

        // 5. 200-Day SMA Variations
        Double sma200 = quote.getTwoHundredDayAverage();
        if (sma200 != null && sma200 > 0 && price != null) {
            if (quote.getTwoHundredDayAverageChange() == null) {
                quote.setTwoHundredDayAverageChange(round(price - sma200, 4));
            }
            if (quote.getTwoHundredDayAverageChangePercent() == null) {
                quote.setTwoHundredDayAverageChangePercent(round((price - sma200) / sma200, 6));
            }
        }

        // 6. Dividend Yield & Rate mutual derivation
        Double divYield = quote.getDividendYield();
        Double divRate = quote.getDividendRate();

        if (divYield != null && divRate == null && price != null && price > 0) {
            divRate = round(price * (divYield / 100.0), 4);
            quote.setDividendRate(divRate);
        } else if (divRate != null && divYield == null && price != null && price > 0) {
            divYield = round((divRate / price) * 100.0, 4);
            quote.setDividendYield(divYield);
        }

        if (market == AssetMarket.B3 && quote.getTrailingAnnualDividendRate() == null && divRate != null) {
            quote.setTrailingAnnualDividendRate(divRate);
        }
        if (market == AssetMarket.B3 && quote.getTrailingAnnualDividendYield() == null && divYield != null) {
            quote.setTrailingAnnualDividendYield(divYield);
        }
        if (market == AssetMarket.B3 && quote.getDividendRate() == null && quote.getTrailingAnnualDividendRate() != null) {
            quote.setDividendRate(quote.getTrailingAnnualDividendRate());
        }
        if (market == AssetMarket.B3 && quote.getDividendYield() == null && quote.getTrailingAnnualDividendYield() != null) {
            quote.setDividendYield(quote.getTrailingAnnualDividendYield());
        }

        return quote;
    }

    /**
     * Enriches {@link Quote} using historical candle series.
     * Computes SMA200, 10-day volume average, 3-month volume average, and 52-week extremes if missing.
     *
     * @param quote   the quote to enrich
     * @param candles the list of historical quotes (daily candles recommended)
     * @return the enriched quote
     */
    public static Quote enrichFromHistory(Quote quote, List<HistoricalQuote> candles) {
        return enrichFromHistory(quote, candles, AssetMarket.B3);
    }

    public static Quote enrichFromHistory(Quote quote, List<HistoricalQuote> candles, AssetMarket market) {
        if (quote == null || candles == null || candles.isEmpty()) {
            return quote;
        }

        List<HistoricalQuote> validCandles = candles.stream()
                .filter(c -> c != null && c.getClose() != null && c.getClose().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparingLong(HistoricalQuote::getDate))
                .toList();

        if (validCandles.isEmpty()) {
            return quote;
        }

        int totalCandles = validCandles.size();

        // 1. SMA200 (last up to 200 daily closes)
        if (quote.getTwoHundredDayAverage() == null && (market == AssetMarket.B3 || totalCandles >= 200)) {
            int smaWindow = Math.min(totalCandles, 200);
            List<HistoricalQuote> smaSlice = validCandles.subList(totalCandles - smaWindow, totalCandles);
            BigDecimal sumCloses = smaSlice.stream()
                    .map(HistoricalQuote::getClose)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            double sma200 = sumCloses.divide(BigDecimal.valueOf(smaWindow), 4, RoundingMode.HALF_UP).doubleValue();
            quote.setTwoHundredDayAverage(sma200);
        }

        // 2. 10-Day Volume Average
        if (quote.getAverageDailyVolume10Day() == null && (market == AssetMarket.B3 || totalCandles >= 10)
                && (market == AssetMarket.B3 || validCandles.subList(totalCandles - 10, totalCandles)
                .stream().allMatch(c -> c.getVolume() != null))) {
            int vol10Window = Math.min(totalCandles, 10);
            List<HistoricalQuote> vol10Slice = validCandles.subList(totalCandles - vol10Window, totalCandles);
            long sumVol10 = vol10Slice.stream()
                    .mapToLong(c -> c.getVolume() != null ? c.getVolume() : 0L)
                    .sum();
            quote.setAverageDailyVolume10Day(sumVol10 / vol10Window);
        }

        // 3. 3-Month Volume Average (~63 trading sessions)
        if (quote.getAverageDailyVolume3Month() == null && (market == AssetMarket.B3 || totalCandles >= 63)
                && (market == AssetMarket.B3 || validCandles.subList(totalCandles - 63, totalCandles)
                .stream().allMatch(c -> c.getVolume() != null))) {
            int vol90Window = Math.min(totalCandles, 63);
            List<HistoricalQuote> vol90Slice = validCandles.subList(totalCandles - vol90Window, totalCandles);
            long sumVol90 = vol90Slice.stream()
                    .mapToLong(c -> c.getVolume() != null ? c.getVolume() : 0L)
                    .sum();
            quote.setAverageDailyVolume3Month(sumVol90 / vol90Window);
        }

        // 4. 52-Week High & Low (~252 trading sessions)
        int yearWindow = Math.min(totalCandles, 252);
        List<HistoricalQuote> yearSlice = validCandles.subList(totalCandles - yearWindow, totalCandles);

        if (quote.getFiftyTwoWeekHigh() == null && (market == AssetMarket.B3 || totalCandles >= 252)) {
            double maxHigh = yearSlice.stream()
                    .map(c -> c.getHigh() != null ? c.getHigh() : c.getClose())
                    .mapToDouble(BigDecimal::doubleValue)
                    .max()
                    .orElse(quote.getRegularMarketPrice() != null ? quote.getRegularMarketPrice() : 0.0);
            if (maxHigh > 0) {
                quote.setFiftyTwoWeekHigh(round(maxHigh, 2));
            }
        }

        if (quote.getFiftyTwoWeekLow() == null && (market == AssetMarket.B3 || totalCandles >= 252)) {
            double minLow = yearSlice.stream()
                    .map(c -> (c.getLow() != null && c.getLow().compareTo(BigDecimal.ZERO) > 0) ? c.getLow() : c.getClose())
                    .mapToDouble(BigDecimal::doubleValue)
                    .filter(v -> v > 0)
                    .min()
                    .orElse(quote.getRegularMarketPrice() != null ? quote.getRegularMarketPrice() : 0.0);
            if (minLow > 0) {
                quote.setFiftyTwoWeekLow(round(minLow, 2));
            }
        }

        // Re-run standard derivations for the newly calculated fields
        return enrich(quote, market);
    }

    /**
     * Enriches {@link Quote} dividend metrics using B3 detailed {@link DividendsData} (proxy da B3).
     * If the provider did not supply dividend rate or yield, this calculates trailing 12-month
     * cash dividends approved/ex in that period.
     *
     * @param quote         the quote to enrich
     * @param dividendsData the detailed dividends data from B3
     * @return the enriched quote
     */
    public static Quote enrichDividendsFromB3(Quote quote, DividendsData dividendsData) {
        if (quote == null || dividendsData == null || dividendsData.getCashDividends() == null) {
            return quote;
        }

        List<CashDividend> cashList = dividendsData.getCashDividends();
        if (cashList.isEmpty()) {
            return quote;
        }

        String symbol = quote.getSymbol();
        String cleanSymbol = symbol != null ? symbol.replace(".SA", "").toUpperCase() : "";
        String expectedShareType = extractShareType(cleanSymbol);

        LocalDate oneYearAgo = LocalDate.now().minusYears(1);

        double trailingSum = 0.0;
        int count = 0;

        for (CashDividend cd : cashList) {
            if (cd == null || cd.getRate() == null) continue;

            // Match asset class if possible (e.g. PN vs ON)
            if (!matchesShareType(cd.getAssetIssued(), cd.getIsinCode(), expectedShareType)) {
                continue;
            }

            LocalDate eventDate = parseDate(cd.getLastDatePrior());
            if (eventDate == null) {
                eventDate = parseDate(cd.getApprovedOn());
            }

            // Include if within the last 365 days
            if (eventDate == null || eventDate.isAfter(oneYearAgo)) {
                try {
                    String cleanRate = cd.getRate().trim().replace(".", "").replace(",", ".");
                    // Check if standard format had a decimal comma
                    if (!cd.getRate().contains(",") && cd.getRate().contains(".")) {
                        cleanRate = cd.getRate().trim();
                    }
                    double rateVal = Double.parseDouble(cleanRate);
                    trailingSum += rateVal;
                    count++;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (trailingSum > 0 && count > 0) {
            double rate = round(trailingSum, 4);
            if (quote.getDividendRate() == null) {
                quote.setDividendRate(rate);
            }
            if (quote.getTrailingAnnualDividendRate() == null) {
                quote.setTrailingAnnualDividendRate(rate);
            }
            if (quote.getRegularMarketPrice() != null && quote.getRegularMarketPrice() > 0) {
                double yield = round((rate / quote.getRegularMarketPrice()) * 100.0, 4);
                if (quote.getDividendYield() == null) {
                    quote.setDividendYield(yield);
                }
                if (quote.getTrailingAnnualDividendYield() == null) {
                    quote.setTrailingAnnualDividendYield(yield);
                }
            }
        }

        return enrich(quote);
    }

    private static String formatRange(Double low, Double high) {
        if (low == null || high == null) return null;
        return String.format(Locale.US, "%.2f - %.2f", low, high);
    }

    private static Double round(Double value, int places) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.trim(), B3_DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractShareType(String ticker) {
        if (ticker.endsWith("3")) return "ON";
        if (ticker.endsWith("4")) return "PN";
        if (ticker.endsWith("11")) return "UNIT";
        return "";
    }

    private static boolean matchesShareType(String assetIssued, String isinCode, String expectedType) {
        if (expectedType.isEmpty()) return true;
        String combined = (assetIssued + " " + isinCode).toUpperCase();
        if (expectedType.equals("ON") && (combined.contains("OR") || combined.contains("ON") || combined.endsWith("3"))) return true;
        if (expectedType.equals("PN") && (combined.contains("PR") || combined.contains("PN") || combined.endsWith("4"))) return true;
        if (expectedType.equals("UNIT") && (combined.contains("UNT") || combined.contains("CD") || combined.contains("CTF") || combined.endsWith("11"))) return true;
        return combined.isEmpty();
    }
}
