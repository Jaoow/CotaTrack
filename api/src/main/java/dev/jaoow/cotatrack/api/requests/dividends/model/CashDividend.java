package dev.jaoow.cotatrack.api.requests.dividends.model;

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
}
