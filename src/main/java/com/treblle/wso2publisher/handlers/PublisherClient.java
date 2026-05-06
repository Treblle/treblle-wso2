
package com.treblle.wso2publisher.handlers;

import org.apache.axis2.util.URL;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.client.entity.GzipCompressingEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import com.treblle.wso2publisher.dto.TrebllePayload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PublisherClient is responsible for sending events.
 */
public class PublisherClient {

    // Logger for logging messages
    private static final Log log = LogFactory.getLog(PublisherClient.class);

    private static final String DEFAULT_URL = "https://ingress.treblle.com";

    // Array of keywords to be masked in the payload
    private static final String[] MASK_KEYWORDS = {
            "password", "pwd", "secret", "password_confirmation", "cc", "card_number", "ccv", "ssn", "credit_score"};
    private final List<String> maskKeywordsList;
    private final Set<String> globalMaskKeywordsSet; // pre-built, lowercased, immutable

    // Pooled HTTP client for connection reuse
    private final CloseableHttpClient httpClient;

    // SDK Token for authentication
    private String sdkToken;

    // API Key for the Treblle project
    private String apiKey;

    // Custom gateway URL, cached at construction time (null means use DEFAULT_URL)
    private final String customGatewayUrl;

    /**
     * Constructor to initialize the PublisherClient with SDK token, API key, and HTTP client.
     *
     * @param sdkToken   the SDK token for authentication
     * @param apiKey     the API key for the Treblle project
     * @param httpClient the pooled HTTP client for connection reuse
     */
    public PublisherClient(String sdkToken, String apiKey, CloseableHttpClient httpClient) {
        this.sdkToken = sdkToken;
        this.apiKey = apiKey;
        this.httpClient = httpClient;

        // Build the final immutable mask keywords list
        List<String> keywords = new ArrayList<>(Arrays.asList(MASK_KEYWORDS));
        String maskKeywordsEnv = System.getenv("ADDITIONAL_MASK_KEYWORDS");
        if (maskKeywordsEnv != null && !maskKeywordsEnv.trim().isEmpty()) {
            for (String kw : maskKeywordsEnv.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    keywords.add(trimmed);
                }
            }
        }
        this.maskKeywordsList = java.util.Collections.unmodifiableList(keywords);

        Set<String> globalSet = new HashSet<>(keywords.size() * 2);
        for (String kw : keywords) {
            globalSet.add(kw.toLowerCase());
        }
        this.globalMaskKeywordsSet = java.util.Collections.unmodifiableSet(globalSet);

        String envGatewayUrl = System.getenv("TREBLLE_GATEWAY_URL");
        this.customGatewayUrl = (envGatewayUrl != null && !envGatewayUrl.trim().isEmpty())
                ? envGatewayUrl.trim() : null;

        log.debug("[TREBLLE]: Masking keywords: " + maskKeywordsList);
    }



    /**
     * publish method is responsible for publishing the event.
     */
    public void publish(TrebllePayload payload) {

        // Null check for payload
        if (payload == null) {
            log.error("[TREBLLE]: Payload is null. Skipping event publication.");
            return;
        }

        // Setting SDK Token and API Key
        payload.setSdkToken(sdkToken);
        payload.setApiKey(apiKey);

        String gatewayUrl = (customGatewayUrl != null) ? customGatewayUrl : DEFAULT_URL;

        int statusCode = 0;
        String reasonPhrase = "";

        final long publishStart = log.isDebugEnabled() ? System.nanoTime() : 0;

        // Use try-with-resources to ensure response is properly closed
        try (CloseableHttpResponse response = maskAndSendPayload(payload, gatewayUrl)) {
            if (response != null) {
                statusCode = response.getStatusLine().getStatusCode();
                reasonPhrase = response.getStatusLine().getReasonPhrase();

                // Log response details for debugging
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]: Response status: " + statusCode + " " + reasonPhrase);
                    log.debug("[TREBLLE]: Response headers: " + java.util.Arrays.toString(response.getAllHeaders()));
                    log.debug(String.format("[TREBLLE]: Performance Total (mask+serialize+HTTP): %.2f ms (status: %d, url: %s)",
                            (System.nanoTime() - publishStart) / 1_000_000.0, statusCode, gatewayUrl));
                }
            }
        } catch (IOException e) {
            log.error("[TREBLLE]: Error closing HTTP response for SDK token: " + sdkToken, e);
        }

        if (statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 204) {
            log.debug("[TREBLLE]: Event successfully published.");
        } else if (statusCode >= 400 && statusCode < 500) {
            log.error("[TREBLLE]: Event publishing failed for SDK token: " + sdkToken + " with status code: " + statusCode
                    + " and reason: " + reasonPhrase + ". Event will be dropped.");
        } else if (statusCode >= 500) {
            log.error("[TREBLLE]: Event publishing failed for SDK token: " + sdkToken + " with status code: " + statusCode
                    + " and reason: " + reasonPhrase + ". Event will be dropped.");
        } else {
            log.error("[TREBLLE]: Event publishing failed for SDK token: " + sdkToken + " with unexpected status code: "
                    + statusCode + ". Event will be dropped.");
        }

    }

    /**
     * Method to mask sensitive data and send the payload to the Treblle service.
     * Uses gzip compression and pooled HTTP client for optimal performance.
     *
     * @param payload the TrebllePayload object to be sent
     * @param baseUrl the base URL of the Treblle service
     * @return the HTTP response from the Treblle service
     */
    private CloseableHttpResponse maskAndSendPayload(TrebllePayload payload, String baseUrl) {

        HttpPost httpPost = new HttpPost(baseUrl);

        String sdkTokenValue = payload.getSdkToken();
        httpPost.setHeader("x-api-key", sdkTokenValue);
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        httpPost.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");

        if (log.isDebugEnabled()) {
            log.debug("[TREBLLE]: Sending request to: " + baseUrl);
            log.debug("[TREBLLE]: x-api-key header set to: " + (sdkTokenValue != null ? sdkTokenValue : "NULL"));
        }

        try {
            final long buildStart = log.isDebugEnabled() ? System.nanoTime() : 0;
            org.json.JSONObject requestBody = buildRequestBodyForTrebllePayload(payload);
            if (log.isDebugEnabled()) {
                log.debug(String.format("[TREBLLE]: Performance buildPayload (mask+serialize): %.2f ms",
                        (System.nanoTime() - buildStart) / 1_000_000.0));
                log.debug("[TREBLLE]: Payload - " + requestBody);
            }

            // Create entity and wrap with gzip compression
            StringEntity uncompressed = new StringEntity(requestBody.toString());
            GzipCompressingEntity gzipEntity = new GzipCompressingEntity(uncompressed);
            httpPost.setEntity(gzipEntity);

            // Use pooled HTTP client for connection reuse
            return httpClient.execute(httpPost);
        } catch (IOException e) {
            log.error("[TREBLLE]: Error sending payload: " + e.getMessage(), e);
        }

        return null;
    }


    /**
     * Method to build the request body for the Treblle payload.
     *
     * @param trebllePayload the TrebllePayload object
     * @return the JSON object representing the request body
     */
    private org.json.JSONObject buildRequestBodyForTrebllePayload(TrebllePayload trebllePayload) {

        org.json.JSONObject requestBody = new org.json.JSONObject();
        requestBody.put("sdk_token", trebllePayload.getSdkToken());
        requestBody.put("api_key", trebllePayload.getApiKey());
        requestBody.put("sdk", "wso2");
        requestBody.put("version", TrebllePayload.TREBLLE_VERSION);

        // Add internal_id and internal_name if available
        if (trebllePayload.getInternalId() != null) {
            requestBody.put("internal_id", trebllePayload.getInternalId());
        }
        if (trebllePayload.getInternalName() != null) {
            requestBody.put("internal_name", trebllePayload.getInternalName());
        }

        org.json.JSONObject data = new org.json.JSONObject();
        data.put("language", new org.json.JSONObject(trebllePayload.getData().getLanguage()));

        org.json.JSONObject request = new org.json.JSONObject();
        request.put("timestamp", trebllePayload.getData().getRequest().getTimestamp());
        request.put("ip", trebllePayload.getData().getRequest().getIp());
        request.put("user_agent", trebllePayload.getData().getRequest().getUserAgent());
        request.put("method", trebllePayload.getData().getRequest().getMethod());
        request.put("url", trebllePayload.getData().getRequest().getUrl());
        request.put("headers", new org.json.JSONObject(trebllePayload.getData().getRequest().getHeaders()));

        // Add route_path if available
        if (trebllePayload.getData().getRequest().getRoutePath() != null) {
            request.put("route_path", trebllePayload.getData().getRequest().getRoutePath());
        }

        request.put("body", parseBodyRaw(trebllePayload.getData().getRequest().getBodyRaw()));

        data.put("request", request);

        org.json.JSONObject response = new org.json.JSONObject();
        response.put("code", trebllePayload.getData().getResponse().getCode());
        response.put("size", trebllePayload.getData().getResponse().getSize());
        response.put("headers", new org.json.JSONObject(trebllePayload.getData().getResponse().getHeaders()));
        response.put("load_time", trebllePayload.getData().getResponse().getLoadTime());

        // If response body capture is disabled, omit body content and reset size
        if (trebllePayload.isDisableResponseBody()) {
            response.put("body", new org.json.JSONObject());
            response.put("size", 0);
        } else {
            response.put("body", parseBodyRaw(trebllePayload.getData().getResponse().getBodyRaw()));
        }

        data.put("response", response);
        data.put("server", new org.json.JSONObject(trebllePayload.getData().getServer()));
        data.put("errors", new org.json.JSONArray(trebllePayload.getData().getErrors()));

        // Build the keyword set: reuse the pre-built global set when no per-API keywords,
        // otherwise copy it and add per-API additions to avoid mutating the shared set.
        List<String> perApiKeywords = trebllePayload.getPerApiMaskKeywords();
        Set<String> keywordsToMask;
        if (perApiKeywords != null && !perApiKeywords.isEmpty()) {
            keywordsToMask = new HashSet<>(globalMaskKeywordsSet);
            for (String kw : perApiKeywords) {
                keywordsToMask.add(kw.toLowerCase());
            }
        } else {
            keywordsToMask = globalMaskKeywordsSet;
        }
        maskKeywordInJson(data, keywordsToMask);

        // Always include metadata object (fields will be null if not populated)
        com.treblle.wso2publisher.dto.Metadata metadata = trebllePayload.getData().getMetadata();
        if (metadata == null) {
            metadata = new com.treblle.wso2publisher.dto.Metadata();
        }
        org.json.JSONObject metadataJson = new org.json.JSONObject();
        metadataJson.put("api_version", metadata.getApiVersion() != null ? metadata.getApiVersion() : org.json.JSONObject.NULL);
        metadataJson.put("user-id", metadata.getUserId() != null ? metadata.getUserId() : org.json.JSONObject.NULL);
        metadataJson.put("publisher", metadata.getPublisher() != null ? metadata.getPublisher() : org.json.JSONObject.NULL);
        metadataJson.put("customer_ip", metadata.getCustomerIp() != null ? metadata.getCustomerIp() : org.json.JSONObject.NULL);
        metadataJson.put("tenant", metadata.getTenant() != null ? metadata.getTenant() : org.json.JSONObject.NULL);
        metadataJson.put("host", metadata.getHost() != null ? metadata.getHost() : org.json.JSONObject.NULL);
        data.put("metadata", metadataJson);

        requestBody.put("data", data);

        return requestBody;
    }

    /**
     * Method to mask sensitive keywords in the JSON object.
     *
     * @param jsonObject the JSON object to be masked
     * @param keyword    the keyword to be masked
     */
    private void maskKeywordInJson(org.json.JSONObject jsonObject, Set<String> keywords) {
        for (Object key : jsonObject.keySet()) {
            String keyStr = key.toString();
            if (keywords.contains(keyStr.toLowerCase())) {
                jsonObject.put(keyStr, "****");
            } else {
                Object value = jsonObject.get(keyStr);
                if (value instanceof org.json.JSONObject) {
                    maskKeywordInJson((org.json.JSONObject) value, keywords);
                } else if (value instanceof org.json.JSONArray) {
                    maskKeywordInArray((org.json.JSONArray) value, keywords);
                }
            }
        }
    }

    private void maskKeywordInArray(org.json.JSONArray array, Set<String> keywords) {
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (item instanceof org.json.JSONObject) {
                maskKeywordInJson((org.json.JSONObject) item, keywords);
            } else if (item instanceof org.json.JSONArray) {
                maskKeywordInArray((org.json.JSONArray) item, keywords);
            }
        }
    }

    /**
     * Parse a raw JSON string into an org.json value (JSONObject or JSONArray).
     * Runs in the worker thread — keeps the critical handler path free of Jackson parsing.
     */
    private Object parseBodyRaw(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new org.json.JSONObject();
        }
        try {
            return new org.json.JSONTokener(raw).nextValue();
        } catch (Exception e) {
            log.debug("[TREBLLE]: Could not parse body as JSON: " + e.getMessage());
            return new org.json.JSONObject();
        }
    }

}
