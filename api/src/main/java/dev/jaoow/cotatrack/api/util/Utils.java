package dev.jaoow.cotatrack.api.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

public class Utils {

    /**
     * Builds a URL query string from the provided parameters.
     *
     * @param params the parameters to build the query string
     * @return the URL query string
     */
    public static String buildUrlParameters(Map<String, String> params) {
        return params.entrySet().stream().map(entry -> {
            String key = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
            String value = URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8);
            return key + "=" + value;
        }).collect(Collectors.joining("&"));
    }
}
