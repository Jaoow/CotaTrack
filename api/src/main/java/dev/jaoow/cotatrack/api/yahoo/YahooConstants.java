package dev.jaoow.cotatrack.api.yahoo;

/**
 * Constants for Yahoo Finance API
 */
public class YahooConstants {

    public static final String QUOTES_QUERY2V7_BASE_URL = "https://query2.finance.yahoo.com/v7/finance/quote";
    public static final String HISTQUOTES_QUERY2V8_BASE_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";

    public static final String COOKIE_SCRAPE_URL = "https://fc.yahoo.com/";
    public static final String CRUMB_SCRAPE_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";

    public static final String YAHOO_CRUMB = System.getProperty("yahoofinance.crumb", "");
    public static final String YAHOO_COOKIE = System.getProperty("yahoofinance.cookie", "");

    public static final String USER_AGENT = System.getProperty(
            "cotatrack.http.useragent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    );

}
