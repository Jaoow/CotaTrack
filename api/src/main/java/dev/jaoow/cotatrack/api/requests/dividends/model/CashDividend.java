package dev.jaoow.cotatrack.api.requests.dividends.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CashDividend {
    private String assetIssued;
    private String paymentDate;
    private String rate;
    private String relatedTo;
    private String approvedOn;
    private String isinCode;
    private String label;
    private String lastDatePrior;
    private String remarks;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String exDate;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String currency;

    public CashDividend(String assetIssued, String paymentDate, String rate, String relatedTo,
                        String approvedOn, String isinCode, String label, String lastDatePrior, String remarks) {
        this(assetIssued, paymentDate, rate, relatedTo, approvedOn, isinCode, label, lastDatePrior, remarks, null, null);
    }
}
