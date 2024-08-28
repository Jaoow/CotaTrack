package dev.jaoow.cotatrack.api.requests.quotes.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jaoow.cotatrack.api.tradingview.LogoIdFetcher;

public class LogoUrlInjector implements DataTransformer {

    private String getLogoUrl(String logoId) {
        if (logoId == null) {
            return System.getProperty("defaultLogoUrl", "");
        }
        return "https://s3-symbol-logo.tradingview.com/" + logoId + ".svg";
    }

    @Override
    public JsonNode transform(JsonNode node) {
        if (node instanceof ObjectNode objectNode && !node.has("logoUrl")) {
            String exchange = node.get("exchange").asText();
            String symbol = node.get("symbol").asText();

            String logoId = LogoIdFetcher.fetchLogoId(exchange, symbol);
            String logoUrl = getLogoUrl(logoId);

            objectNode.put("logoUrl", logoUrl);
        }
        return node;
    }
}
