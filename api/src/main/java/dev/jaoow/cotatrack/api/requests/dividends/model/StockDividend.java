package dev.jaoow.cotatrack.api.requests.dividends.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockDividend {
    private String assetIssued;
    private String factor;
    private String approvedOn;
    private String isinCode;
    private String label;
    private String lastDatePrior;
    private String remarks;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String exDate;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String eventType;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal fromFactor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal toFactor;

    public StockDividend(String assetIssued, String factor, String approvedOn, String isinCode,
                         String label, String lastDatePrior, String remarks) {
        this(assetIssued, factor, approvedOn, isinCode, label, lastDatePrior, remarks, null, null, null, null);
    }
}
