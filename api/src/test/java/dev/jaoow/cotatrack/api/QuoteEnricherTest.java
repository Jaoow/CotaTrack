package dev.jaoow.cotatrack.api;

import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.requests.dividends.model.CashDividend;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QuoteEnricherTest {

    @Test
    @DisplayName("Should derive dayRange, 52w range, 52w variations, and SMA200 variations")
    void shouldDeriveBasicIntraQuoteFields() {
        Quote quote = Quote.builder()
                .symbol("PETR4.SA")
                .regularMarketPrice(100.0)
                .regularMarketDayLow(95.0)
                .regularMarketDayHigh(105.0)
                .fiftyTwoWeekLow(80.0)
                .fiftyTwoWeekHigh(120.0)
                .twoHundredDayAverage(80.0)
                .dividendYield(10.0)
                .build();

        Quote enriched = QuoteEnricher.enrich(quote);

        // Day range
        assertThat(enriched.getRegularMarketDayRange()).isEqualTo("95.00 - 105.00");

        // 52-week range and variations
        assertThat(enriched.getFiftyTwoWeekRange()).isEqualTo("80.00 - 120.00");
        assertThat(enriched.getFiftyTwoWeekHighChange()).isEqualTo(-20.0);
        assertThat(enriched.getFiftyTwoWeekHighChangePercent()).isCloseTo(-0.166667, within(0.0001));
        assertThat(enriched.getFiftyTwoWeekLowChange()).isEqualTo(20.0);

        // 200-day SMA variations
        assertThat(enriched.getTwoHundredDayAverageChange()).isEqualTo(20.0);
        assertThat(enriched.getTwoHundredDayAverageChangePercent()).isCloseTo(0.25, within(0.0001));

        // Dividend cross derivation: yield=10% on price 100 -> rate = 10.0
        assertThat(enriched.getDividendRate()).isEqualTo(10.0);
        assertThat(enriched.getTrailingAnnualDividendRate()).isEqualTo(10.0);
        assertThat(enriched.getTrailingAnnualDividendYield()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("Should derive SMA200, 10d/3m volumes, and 52w extremes from historical candles")
    void shouldDeriveMetricsFromHistory() {
        Quote quote = Quote.builder()
                .symbol("VALE3.SA")
                .regularMarketPrice(60.0)
                .build();

        List<HistoricalQuote> candles = new ArrayList<>();
        long baseTimestamp = 1700000000L;

        // Generate 200 daily candles with predictable values (10.0 to 209.0)
        for (int i = 1; i <= 200; i++) {
            BigDecimal price = BigDecimal.valueOf(10 + i); // prices 11.0 to 210.0
            candles.add(new HistoricalQuote(
                    baseTimestamp + (i * 86400L),
                    price,
                    price.subtract(BigDecimal.ONE),
                    price.add(BigDecimal.ONE),
                    price,
                    price,
                    1000L
            ));
        }

        Quote enriched = QuoteEnricher.enrichFromHistory(quote, candles);

        // SMA200 = sum(11..210) / 200 = 110.5
        assertThat(enriched.getTwoHundredDayAverage()).isEqualTo(110.5);
        assertThat(enriched.getTwoHundredDayAverageChange()).isEqualTo(60.0 - 110.5);

        // Volume averages: all candles have 1000 volume
        assertThat(enriched.getAverageDailyVolume10Day()).isEqualTo(1000L);
        assertThat(enriched.getAverageDailyVolume3Month()).isEqualTo(1000L);

        // 52-week extremes: min = 10.0 (low was 11 - 1 = 10), max = 211.0 (high was 210 + 1)
        assertThat(enriched.getFiftyTwoWeekHigh()).isEqualTo(211.0);
        assertThat(enriched.getFiftyTwoWeekLow()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("Should derive dividend rate and yield from B3 cash dividends trailing 12 months")
    void shouldDeriveDividendsFromB3Proxy() {
        Quote quote = Quote.builder()
                .symbol("PETR4.SA")
                .regularMarketPrice(50.0)
                .build();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String recentDate = LocalDate.now().minusMonths(2).format(fmt);
        String oldDate = LocalDate.now().minusMonths(15).format(fmt);

        CashDividend div1 = new CashDividend();
        div1.setAssetIssued("BRPETRACNPR6"); // matches PETR4 (PN)
        div1.setRate("2,50");
        div1.setLastDatePrior(recentDate);

        CashDividend div2 = new CashDividend();
        div2.setAssetIssued("BRPETRACNPR6");
        div2.setRate("1,50");
        div2.setLastDatePrior(recentDate);

        // Old dividend (> 1 year ago, should be excluded from trailing 12m)
        CashDividend divOld = new CashDividend();
        divOld.setAssetIssued("BRPETRACNPR6");
        divOld.setRate("10,00");
        divOld.setLastDatePrior(oldDate);

        DividendsData data = new DividendsData(List.of(div1, div2, divOld), List.of());

        Quote enriched = QuoteEnricher.enrichDividendsFromB3(quote, data);

        // Sum = 2.50 + 1.50 = 4.00
        assertThat(enriched.getDividendRate()).isEqualTo(4.0);
        assertThat(enriched.getTrailingAnnualDividendRate()).isEqualTo(4.0);

        // Yield = (4.0 / 50.0) * 100 = 8.0%
        assertThat(enriched.getDividendYield()).isEqualTo(8.0);
        assertThat(enriched.getTrailingAnnualDividendYield()).isEqualTo(8.0);
    }
}
