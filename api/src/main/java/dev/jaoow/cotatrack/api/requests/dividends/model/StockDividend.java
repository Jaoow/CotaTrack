package dev.jaoow.cotatrack.api.requests.dividends.model;

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
}
