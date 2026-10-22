package dev.jaoow.cotatrack.api.provider;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuoteMetadata {
    String market;
    String provider;
    String feed;
    String mode;
    Date asOf;
    String historicalFeed;
    boolean fromCache;
    boolean stale;
}
