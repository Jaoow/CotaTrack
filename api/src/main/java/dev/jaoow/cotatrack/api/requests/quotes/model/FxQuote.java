package dev.jaoow.cotatrack.api.requests.quotes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a foreign exchange (FX) quote with market data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FxQuote {

    private String symbol;
    private String name;
    private String currency;

    @JsonProperty("regularMarketPrice")
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private String quoteDate;
    private String provider;

    public BigDecimal getPrice() {
        if (price != null && !price.equals(BigDecimal.ZERO)) {
            return price;
        }
        return sellPrice != null ? sellPrice : BigDecimal.ZERO;
    }

    public void setRegularMarketPrice(BigDecimal regularMarketPrice) {
        this.price = regularMarketPrice;
    }

    @JsonIgnore
    public BigDecimal getRegularMarketPrice() {
        return getPrice();
    }
}