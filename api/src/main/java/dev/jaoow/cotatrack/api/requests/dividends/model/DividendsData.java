package dev.jaoow.cotatrack.api.requests.dividends.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DividendsData {
    private List<CashDividend> cashDividends;
    private List<StockDividend> stockDividends;
}
