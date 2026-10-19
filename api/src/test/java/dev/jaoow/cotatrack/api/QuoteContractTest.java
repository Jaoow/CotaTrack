package dev.jaoow.cotatrack.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.provider.enricher.QuoteEnricher;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuoteContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Should strictly omit null fields in JSON serialization to prevent false 0.0 values")
    void testContractNullOmission() throws Exception {
        Quote quote = new Quote();
        quote.setSymbol("BOVA11.SA");
        quote.setRegularMarketPrice(201.50);
        quote.setCurrency("BRL");

        // Explicitly null fields
        quote.setDividendRate(null);
        quote.setDividendYield(null);
        quote.setMarketCap(null);
        quote.setTwoHundredDayAverage(null);

        String json = mapper.writeValueAsString(quote);

        // Verify that null fields do NOT appear as 0.0 or 0
        assertFalse(json.contains("\"dividendYield\""), "dividendYield must be omitted when null");
        assertFalse(json.contains("\"dividendRate\""), "dividendRate must be omitted when null");
        assertFalse(json.contains("\"marketCap\""), "marketCap must be omitted when null");
        assertFalse(json.contains("\"twoHundredDayAverage\""), "twoHundredDayAverage must be omitted when null");

        // Verify present fields are included
        assertTrue(json.contains("\"symbol\":\"BOVA11.SA\""));
        assertTrue(json.contains("\"regularMarketPrice\":201.5"));
    }

    @Test
    @DisplayName("Should derive dayRange, fiftyTwoWeekRange, and percentage changes accurately")
    void testContractDerivedFields() {
        Quote q = new Quote();
        q.setSymbol("PETR4.SA");
        q.setRegularMarketPrice(38.50);
        q.setRegularMarketDayLow(38.40);
        q.setRegularMarketDayHigh(39.15);
        q.setFiftyTwoWeekLow(28.10);
        q.setFiftyTwoWeekHigh(42.50);

        Quote enriched = QuoteEnricher.enrich(q);

        assertEquals("38.40 - 39.15", enriched.getRegularMarketDayRange());
        assertEquals("28.10 - 42.50", enriched.getFiftyTwoWeekRange());

        // 52-week high change: 38.50 - 42.50 = -4.00
        assertEquals(-4.00, enriched.getFiftyTwoWeekHighChange(), 0.001);
        // Change percent: -4.00 / 42.50 = -0.0941
        assertEquals(-0.0941, enriched.getFiftyTwoWeekHighChangePercent(), 0.001);

        // 52-week low change: 38.50 - 28.10 = 10.40
        assertEquals(10.40, enriched.getFiftyTwoWeekLowChange(), 0.001);
    }
}
