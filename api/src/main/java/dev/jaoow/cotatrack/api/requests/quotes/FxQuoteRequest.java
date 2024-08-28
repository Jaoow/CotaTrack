package dev.jaoow.cotatrack.api.requests.quotes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.jaoow.cotatrack.api.requests.quotes.model.FxQuote;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FxQuoteRequest extends QuoteRequest<FxQuote> {

    public FxQuoteRequest(String symbols) {
        super(symbols);
    }

    @Override
    protected FxQuote parseJson(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, FxQuote.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON", e);
            return null;
        }
    }
}
