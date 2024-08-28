package dev.jaoow.cotatrack.api.requests.dividends.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

@Slf4j
public class DividendsFetcher {

    private static final String GET_LISTED_SUPPLEMENT_COMPANY = "https://sistemaswebb3-listados.b3.com.br/listedCompaniesProxy/CompanyCall/GetListedSupplementCompany/";
    private static final String DEFAULT_LANGUAGE = "pt-br";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .sslContext(createSslContext())
            .build();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static JsonNode fetchDividendsData(String symbol) throws IOException, InterruptedException {
        CompanyRequest requestPayload = new CompanyRequest(symbol, DEFAULT_LANGUAGE);

        // Convert the payload to a JSON string and encode it in Base64
        byte[] payloadBytes = objectMapper.writeValueAsString(requestPayload).getBytes(StandardCharsets.UTF_8);
        String payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes);

        String url = GET_LISTED_SUPPLEMENT_COMPANY + payloadBase64;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return objectMapper.readTree(response.body()); // The response is an array with a single object
        } else {
            throw new IOException("Failed to fetch data: HTTP status code " + response.statusCode());
        }
    }

    private static SSLContext createSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Data
    @AllArgsConstructor
    private static class CompanyRequest {
        private String issuingCompany;
        private String language;
    }
}
