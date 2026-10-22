package dev.jaoow.cotatrack.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.jaoow.cotatrack.api.provider.QuoteMetadata;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsStockResult(String name, String longName, String currency, String exchange, String type,
                            Long volume, BigDecimal open, BigDecimal close,
                            @JsonProperty("change_abs") BigDecimal change, QuoteMetadata metadata) {
}
