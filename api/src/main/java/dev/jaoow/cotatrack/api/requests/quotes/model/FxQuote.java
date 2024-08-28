package dev.jaoow.cotatrack.api.requests.quotes.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Represents a foreign exchange (FX) quote with market data.
 */
@Data
public class FxQuote {

    private String symbol;

    @JsonProperty("regularMarketPrice")
    private BigDecimal price = BigDecimal.ZERO;

}