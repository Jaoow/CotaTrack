package dev.jaoow.cotatrack.api.yahoo;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Class to handle the Yahoo Finance credentials.
 */
@Slf4j
public class YahooCredentials {

    private static final CookieManager cookieManager = new CookieManager();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager) // Use the cookie manager to store the cookie
            .build();

    private static String crumb = "";
    private static String cookie = "";

    static {
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
    }

    /**
     * Fetch the cookie from Yahoo Finance.
     *
     * @throws IOException          if the cookie cannot be fetched
     * @throws InterruptedException if the HTTP request is interrupted
     */
    private static void fetchCookie() throws IOException, InterruptedException {
        if (YahooConstants.YAHOO_COOKIE != null && !YahooConstants.YAHOO_COOKIE.isEmpty()) {
            cookie = YahooConstants.YAHOO_COOKIE;
            log.info("Set cookie from system property: {}", cookie);
            return;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(YahooConstants.COOKIE_SCRAPE_URL))
                .header("User-Agent", YahooConstants.USER_AGENT)
                .build();

        // Send the request, so the cookie jar is populated
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Cookie scrape request returned HTTP status {}", response.statusCode());

        // Check Set-Cookie headers first
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            if (setCookie.contains("A3=")) {
                cookie = setCookie;
                log.info("Set cookie from Set-Cookie header: {}", cookie);
                return;
            }
        }

        // Get the cookie from the cookie jar
        CookieStore cookieJar = cookieManager.getCookieStore();
        for (HttpCookie httpCookie : cookieJar.getCookies()) {
            cookie = httpCookie.toString();
            log.info("Set cookie from cookie jar: {}", cookie);
            return;
        }

        log.warn("Could not find A3 cookie from HTTP response (status {}).", response.statusCode());
    }

    /**
     * Fetch the crumb from Yahoo Finance.
     *
     * @throws IOException          if the crumb cannot be fetched
     * @throws InterruptedException if the HTTP request is interrupted
     */
    private static void fetchCrumb() throws IOException, InterruptedException {
        if (YahooConstants.YAHOO_CRUMB != null && !YahooConstants.YAHOO_CRUMB.isEmpty()) {
            crumb = YahooConstants.YAHOO_CRUMB;
            log.info("Set crumb from system property: {}", crumb);
            return;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(YahooConstants.CRUMB_SCRAPE_URL))
                .header("User-Agent", YahooConstants.USER_AGENT);

        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.header("Cookie", cookie);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String crumbResult = response.body().trim();
            if (!crumbResult.isEmpty() && !crumbResult.contains("<html>")) {
                crumb = crumbResult;
                log.info("Set crumb from HTTP request: {}", crumb);
                return;
            }
        }

        log.warn("Failed to set crumb from HTTP request (status {}). Quote requests may fail or fall back.", response.statusCode());
    }

    /**
     * Get the crumb and cookie from Yahoo Finance with retry.
     * <p>
     * We need to get the crumb and cookie from Yahoo Finance to be able to make requests to the API.
     * The order of the requests is important, as the crumb request needs the cookie to be set.
     * </p>
     */
    public static synchronized void fetch() {
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                fetchCookie();
                fetchCrumb();
                if (crumb != null && !crumb.isEmpty()) {
                    log.info("Successfully fetched Yahoo credentials on attempt {}", attempt);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while fetching Yahoo credentials");
                return;
            } catch (Exception e) {
                log.warn("Attempt {} to refresh crumb/cookie failed: {}", attempt, e.getMessage());
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Could not obtain a fresh Yahoo crumb after retries. Proceeding without fresh credentials.");
    }

    /**
     * Forces credential reset and refresh.
     */
    public static synchronized void refresh() {
        crumb = "";
        cookie = "";
        cookieManager.getCookieStore().removeAll();
        fetch();
    }

    /**
     * Get the crumb.
     *
     * @return the crumb
     */
    public static synchronized String getCrumb() {
        if (crumb == null || crumb.isEmpty()) {
            log.info("Crumb is empty. Trying to refresh.");
            fetch();
        }
        return crumb != null ? crumb : "";
    }

    /**
     * Get the cookie.
     *
     * @return the cookie
     */
    public static synchronized String getCookie() {
        if (cookie == null || cookie.isEmpty()) {
            log.info("Cookie is empty. Trying to refresh.");
            fetch();
        }
        return cookie != null ? cookie : "";
    }
}
