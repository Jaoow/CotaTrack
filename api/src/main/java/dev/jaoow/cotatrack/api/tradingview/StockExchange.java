package dev.jaoow.cotatrack.api.tradingview;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Class used map the exchange code from Yahoo Finance to the TradingView exchange code.
 */
@Getter
@RequiredArgsConstructor
public enum StockExchange {
    NYQ("NYSE"),
    NMS("NASDAQ"),
    ASE("AMEX"),
    SAO("BMFBOVESPA"),
    LSE("LSE"),
    TYO("TSE"),
    HKG("HKEX"),
    SHA("SSE"),
    SHZ("SZSE"),
    TOR("TSX"),
    FRA("FSE"),
    JSE("JSE"),
    XETRA("XETRA");

    // TradingView exchange code
    private final String exchangeCode;

}
