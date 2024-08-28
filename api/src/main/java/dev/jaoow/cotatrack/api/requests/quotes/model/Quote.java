package dev.jaoow.cotatrack.api.requests.quotes.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.jaoow.cotatrack.api.requests.dividends.model.DividendsData;
import dev.jaoow.cotatrack.api.requests.dividends.DividendsRequest;
import dev.jaoow.cotatrack.api.requests.historicalquotes.model.HistoricalQuote;
import dev.jaoow.cotatrack.api.requests.historicalquotes.HistoricalQuotesRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * Represents a stock quote with market data, historical and dividends information.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(
        ignoreUnknown = true,
        value = {"exchange", "fullExchangeName"}
)
public class Quote {

    private String symbol;
    private String currency;

    private String exchange;
    private String fullExchangeName;

    private String shortName;
    private String longName;

    private String logoUrl;

    private double regularMarketChange;
    private double regularMarketChangePercent;
    private Date regularMarketTime;

    private double regularMarketPrice;
    private double regularMarketDayHigh;
    private String regularMarketDayRange;
    private double regularMarketDayLow;
    private long regularMarketVolume;
    private double regularMarketPreviousClose;
    private double regularMarketOpen;

    private long averageDailyVolume3Month;
    private long averageDailyVolume10Day;

    private double twoHundredDayAverage;
    private double twoHundredDayAverageChange;
    private double twoHundredDayAverageChangePercent;

    private double fiftyTwoWeekLowChange;
    private String fiftyTwoWeekRange;
    private double fiftyTwoWeekHighChange;
    private double fiftyTwoWeekHighChangePercent;
    private double fiftyTwoWeekLow;
    private double fiftyTwoWeekHigh;

    private double trailingAnnualDividendRate;
    private double trailingAnnualDividendYield;

    private double dividendRate;
    private double dividendYield;

    private long marketCap;

    private List<HistoricalQuote> historicalQuotes;
    private DividendsData dividends;

    /**
     * Requests historical quotes for this stock from the specified start date to the specified end date and interval.
     *
     * @param historicalRequest the historical quotes request
     */
    public void fetchHistoricalQuotes(HistoricalQuotesRequest historicalRequest) {
        if (this.historicalQuotes != null) {
            return;
        }

        this.setHistoricalQuotes(historicalRequest.getResult());
    }

    /**
     * Returns the dividends data for this stock.
     * If the dividends data is not available yet, it will be requested from B3.
     */
    public void fetchDividendsData() {
        if (this.dividends != null) {
            return;
        }

        DividendsRequest dividendsRequest = new DividendsRequest(this.symbol);
        this.setDividends(dividendsRequest.getResult());
    }
}