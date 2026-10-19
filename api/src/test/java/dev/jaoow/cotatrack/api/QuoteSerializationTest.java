package dev.jaoow.cotatrack.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should omit null fields when serializing Quote instead of outputting 0 or 0.0")
    void shouldOmitNullFieldsWhenSerializing() throws Exception {
        Quote quote = Quote.builder()
                .symbol("PETR4.SA")
                .currency("BRL")
                .regularMarketPrice(58.24)
                .build();

        String json = objectMapper.writeValueAsString(quote);

        // Fields that were set must be present
        assertThat(json).contains("\"symbol\":\"PETR4.SA\"");
        assertThat(json).contains("\"currency\":\"BRL\"");
        assertThat(json).contains("\"regularMarketPrice\":58.24");

        // Fields that were NOT set must NOT be present as 0, 0.0, or false values
        assertThat(json).doesNotContain("\"twoHundredDayAverage\"");
        assertThat(json).doesNotContain("\"dividendYield\"");
        assertThat(json).doesNotContain("\"dividendRate\"");
        assertThat(json).doesNotContain("\"trailingAnnualDividendRate\"");
        assertThat(json).doesNotContain("\"marketCap\"");
        assertThat(json).doesNotContain("\"averageDailyVolume10Day\"");
        assertThat(json).doesNotContain("\"averageDailyVolume3Month\"");
    }

    @Test
    @DisplayName("Should deserialize missing numeric fields as null, not primitive 0")
    void shouldDeserializeMissingFieldsAsNull() throws Exception {
        String json = "{\"symbol\":\"VALE3.SA\",\"regularMarketPrice\":66.50}";

        Quote quote = objectMapper.readValue(json, Quote.class);

        assertThat(quote.getSymbol()).isEqualTo("VALE3.SA");
        assertThat(quote.getRegularMarketPrice()).isEqualTo(66.50);

        // Unset fields must be null
        assertThat(quote.getDividendYield()).isNull();
        assertThat(quote.getTwoHundredDayAverage()).isNull();
        assertThat(quote.getMarketCap()).isNull();
        assertThat(quote.getRegularMarketVolume()).isNull();
    }
}
