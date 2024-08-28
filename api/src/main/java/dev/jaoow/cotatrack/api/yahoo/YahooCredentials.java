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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36")
                .build();

        // Send the request, so the cookie jar is populated
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Get the cookie from the cookie jar
        CookieStore cookieJar = cookieManager.getCookieStore();
        for (HttpCookie httpCookie : cookieJar.getCookies()) {
            cookie = httpCookie.toString();
            log.info("Set cookie from cookie jar: {}", cookie);
            return;
        }

        throw new IOException("Failed to set cookie from HTTP request.");
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(YahooConstants.CRUMB_SCRAPE_URL))
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String crumbResult = response.body().trim();

        if (!crumbResult.isEmpty()) {
            crumb = crumbResult;
            log.info("Set crumb from HTTP request: {}", crumb);
        } else {
            log.warn("Failed to set crumb from HTTP request. Historical quote requests will most likely fail.");
        }
    }

    /**
     * Get the crumb and cookie from Yahoo Finance.
     * <p>
     * We need to get the crumb and cookie from Yahoo Finance to be able to make requests to the API.
     * The order of the requests is important, as the crumb request needs the cookie to be set.
     * </p>
     */
    public static void fetch(){
        try {
            fetchCookie();
            fetchCrumb();
        } catch (InterruptedException | IOException e) {
            log.error("Failed to refresh crumb and cookie", e);
        }
    }

    /**
     * Get the crumb.
     *
     * @return the crumb
     */
    public static synchronized String getCrumb() {
        if (crumb == null || crumb.isEmpty()) {
            log.warn("Crumb is empty. Trying to refresh.");
            fetch();
        }
        return crumb;
    }

    /**
     * Get the cookie.
     *
     * @return the cookie
     */
    public static synchronized String getCookie() {
        if (cookie == null || cookie.isEmpty()) {
            log.warn("Cookie is empty. Trying to refresh.");
            fetch();
        }
        return cookie;
    }
}
