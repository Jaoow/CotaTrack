package dev.jaoow.cotatrack.api.tradingview.field;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StockField implements FieldKey {

    CHANGE("change_abs"),
    CHANGE_PERCENT("change"),
    OPEN("open"),
    CLOSE("close"),
    EXCHANGE("exchange"),
    HIGH("high"),
    LOW("low"),
    LOGOID("logoid"),
    NAME("name"),
    DESCRIPTION("description"),
    SECTOR("sector"),
    TYPE("type"),
    SUBTYPE("subtype"),
    MARKET_CAPITALIZATION("market_cap_basic"),
    VOLUME("volume");

    private final String key;
}
