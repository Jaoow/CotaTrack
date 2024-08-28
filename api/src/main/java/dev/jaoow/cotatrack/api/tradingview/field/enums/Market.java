package dev.jaoow.cotatrack.api.tradingview.field.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Market {
    AMERICA("america"),
    BRAZIL("brazil");

    private final String value;

}
