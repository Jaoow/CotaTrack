package dev.jaoow.cotatrack.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class StockResult {

    private String name;
    private int volume;

    @JsonProperty("change_abs")
    private BigDecimal change;

    private BigDecimal open;
    private BigDecimal close;

}
