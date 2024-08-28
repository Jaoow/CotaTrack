package dev.jaoow.cotatrack.api.requests.quotes.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MarketTimeConverter implements DataTransformer {

    @Override
    public JsonNode transform(JsonNode node) {
        if (node instanceof ObjectNode objectNode && node.has("regularMarketTime")) {
            long timestamp = node.get("regularMarketTime").asLong();
            node = objectNode.put("regularMarketTime", timestamp * 1000);
        }
        return node;
    }
}
