package dev.jaoow.cotatrack.api.tradingview.field.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Type {
    STOCK("stock"),
    DEPOSITORY_RECEIPT("dr"),
    FUND("fund"),
    STRUCTURED("structured");

    private final String value;

    public static Type fromTag(String value) {
        for (Type type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
