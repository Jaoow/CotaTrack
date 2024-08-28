package dev.jaoow.cotatrack.api.requests.quotes.transform;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A data transformer is used to transform the JSON data before parsing it.
 * This can be used to inject additional data or modify the existing data.
 */
public interface DataTransformer {
    JsonNode transform(JsonNode node);
}
