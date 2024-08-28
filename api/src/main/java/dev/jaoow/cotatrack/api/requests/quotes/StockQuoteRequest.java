package dev.jaoow.cotatrack.api.requests.quotes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.jaoow.cotatrack.api.requests.quotes.model.Quote;
import dev.jaoow.cotatrack.api.requests.quotes.transform.LogoUrlInjector;
import dev.jaoow.cotatrack.api.requests.quotes.transform.MarketTimeConverter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StockQuoteRequest extends QuoteRequest<Quote> {

    public StockQuoteRequest(String symbols) {
        super(symbols);
        addTransformer(new LogoUrlInjector());
        addTransformer(new MarketTimeConverter());
    }

    @Override
    protected Quote parseJson(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, Quote.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON", e);
            return null;
        }
    }
}
