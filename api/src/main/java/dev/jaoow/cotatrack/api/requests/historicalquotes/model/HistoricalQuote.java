package dev.jaoow.cotatrack.api.requests.historicalquotes.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a historical quote with market data for a specific date.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class HistoricalQuote {
    private long date;
    private BigDecimal open;
    private BigDecimal low;
    private BigDecimal high;
    private BigDecimal close;
    private BigDecimal adjClose;
    private Long volume;
}