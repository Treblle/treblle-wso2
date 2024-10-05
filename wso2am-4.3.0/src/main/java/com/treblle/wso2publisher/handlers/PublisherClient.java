
package com.treblle.wso2publisher.handlers;

import org.apache.axis2.util.URL;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.treblle.wso2publisher.dto.RuntimeError;
import com.treblle.wso2publisher.dto.TrebllePayload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * PublisherClient is responsible for sending events.
 */
public class PublisherClient {

    // Logger for logging messages
    private static final Log log = LogFactory.getLog(PublisherClient.class);

    // Array of base URLs for the Treblle service
    private static final String[] BASE_URLS = {
            "https://rocknrolla.treblle.com",
            "https://punisher.treblle.com",
            "https://sicario.treblle.com"
    };

    // Array of keywords to be masked in the payload
    private static final String[] MASK_KEYWORDS = {
            "password", "pwd", "secret", "password_confirmation", "cc", "card_number", "ccv", "ssn", "credit_score"};
    List<String> maskKeywordsList = new ArrayList<>(Arrays.asList(MASK_KEYWORDS));

    // API key for authentication
    private String apiKey;


    /**
     * Constructor to initialize the PublisherClient with API key and project ID.
     *
     * @param apiKey    the API key for authentication
     */
    public PublisherClient(String apiKey) {
        this.apiKey = apiKey;

        // Retrieve additional mask keywords from environment variable
        String maskKeywordsEnv = System.getenv("ADDITIONAL_MASK_KEYWORDS");
        if (maskKeywordsEnv != null) {
            String[] maskKeywordsEnvArray = maskKeywordsEnv.split(",");
            maskKeywordsList.addAll(Arrays.asList(maskKeywordsEnvArray));
        }

        log.debug("Masking keywords: " + maskKeywordsList);
    }


    /**
     * Method to retry publishing the payload.
     *
     * @param payload the TrebllePayload object to be published
     */
    private void doRetry(TrebllePayload payload) {

        String API_ID = payload.getApiId();

        Integer currentAttempt = PublisherClientContextHolder.PUBLISH_ATTEMPTS.get();

        if (currentAttempt > 0) {
            currentAttempt -= 1;
            PublisherClientContextHolder.PUBLISH_ATTEMPTS.set(currentAttempt);
            try {
                Thread.sleep(10000);
                publish(payload);
            } catch (InterruptedException e) {
                log.error("Failing retry attempt at Publisher client", e);
            }
        } else if (currentAttempt == 0) {
            log.error("Failed all retrying attempts. Event will be dropped for api id: " + API_ID);
        }
    }

    /**
     * publish method is responsible for publishing the event.
     */
    public void publish(TrebllePayload payload) {

         String API_ID = payload.getApiId();

        // Setting API Key and Project ID
        payload.setApiKey(apiKey);

        String randomBaseUrl = getRandomBaseUrl();
        CloseableHttpResponse response = maskAndSendPayload(payload, randomBaseUrl);

        int statusCode = 0;
        if (response != null) {
            statusCode = response.getStatusLine().getStatusCode();
        }

        if (statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 204) {
            log.debug("Event successfully published.");
        } else if (statusCode >= 400 && statusCode < 500) {
            log.error("Event publishing failed for api id: " + API_ID + " with status code: " + statusCode
                    + " and reason: "
                    + response.getStatusLine().getReasonPhrase());
        } else {
            log.error("Event publishing failed for for api id: " + API_ID + ". Retrying...");
            doRetry(payload);
        }

    }

    /**
     * Method to get a random base URL from the list of base URLs.
     *
     * @return a random base URL
     */
    private static String getRandomBaseUrl() {
        Random random = new Random();
        int index = random.nextInt(BASE_URLS.length);
        return BASE_URLS[index];
    }


    /**
     * Method to mask sensitive data and send the payload to the Treblle service.
     *
     * @param payload the TrebllePayload object to be sent
     * @param baseUrl the base URL of the Treblle service
     * @return the HTTP response from the Treblle service
     */
    private CloseableHttpResponse maskAndSendPayload(TrebllePayload payload, String baseUrl) {

        final List<RuntimeError> errors = new ArrayList<>(2);
        if (!errors.isEmpty()) {
            payload.getData().setErrors(errors);
        }

        URL serviceEndpointURL = new URL(baseUrl);
        HttpClient httpClient = APIUtil.getHttpClient(serviceEndpointURL.getPort(),
                serviceEndpointURL.getProtocol());
        HttpPost httpPost = new HttpPost(baseUrl);
        httpPost.setHeader("x-api-key", payload.getApiKey());
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        StringEntity params;

        try {
            org.json.JSONObject requestBody = buildRequestBodyForTrebllePayload(payload);
            log.debug("Treblle Payload - " + requestBody);
            params = new StringEntity(requestBody.toString());
            httpPost.setEntity(params);
            return APIUtil.executeHTTPRequest(httpPost, httpClient);
        } catch (IOException | APIManagementException e) {
            log.error(e.getMessage());
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
        requestBody.put("api_key", trebllePayload.getApiKey());
        requestBody.put("api_id", trebllePayload.getApiId());
        requestBody.put("sdk", "wso2");
        requestBody.put("version", "0.1");

        org.json.JSONObject data = new org.json.JSONObject();
        data.put("language", new org.json.JSONObject(trebllePayload.getData().getLanguage()));

        org.json.JSONObject request = new org.json.JSONObject();
        request.put("timestamp", trebllePayload.getData().getRequest().getTimestamp());
        request.put("ip", trebllePayload.getData().getRequest().getIp());
        request.put("user_agent", trebllePayload.getData().getRequest().getUserAgent());
        request.put("method", trebllePayload.getData().getRequest().getMethod());
        request.put("url", trebllePayload.getData().getRequest().getUrl());
        request.put("headers", new org.json.JSONObject(trebllePayload.getData().getRequest().getHeaders()));

        JsonNode reqBody = trebllePayload.getData().getRequest().getBody();
        if (reqBody != null) {
            request.put("body", new org.json.JSONObject(reqBody.toString()));
        } else {
            request.put("body", new org.json.JSONObject());
        }

        data.put("request", request);

        org.json.JSONObject response = new org.json.JSONObject();
        response.put("code", trebllePayload.getData().getResponse().getCode());
        response.put("size", trebllePayload.getData().getResponse().getSize());
        response.put("headers", new org.json.JSONObject(trebllePayload.getData().getResponse().getHeaders()));
        response.put("load_time", trebllePayload.getData().getResponse().getLoadTime());

        JsonNode responseBody = trebllePayload.getData().getResponse().getBody();
        if (responseBody != null) {
            response.put("body", new org.json.JSONObject(responseBody.toString()));
        } else {
            response.put("body", new org.json.JSONObject());
        }

        data.put("response", response);
        data.put("server", new org.json.JSONObject(trebllePayload.getData().getServer()));
        data.put("errors", new org.json.JSONArray(trebllePayload.getData().getErrors()));

        for (String keyword : maskKeywordsList) {
            maskKeywordInJson(data, keyword);
        }
        requestBody.put("data", data);

        return requestBody;
    }

    /**
     * Method to mask sensitive keywords in the JSON object.
     *
     * @param jsonObject the JSON object to be masked
     * @param keyword    the keyword to be masked
     */
    private void maskKeywordInJson(org.json.JSONObject jsonObject, String keyword) {
        String lowerCaseKeyword = keyword.toLowerCase();
        for (Object key : jsonObject.keySet()) {
            String lowerCaseKey = key.toString().toLowerCase();
            if (lowerCaseKey.equals(lowerCaseKeyword)) {
                jsonObject.put(key.toString(), "****");
            } else {
                Object value = jsonObject.get(key.toString());
                if (value instanceof org.json.JSONObject) {
                    maskKeywordInJson((org.json.JSONObject) value, keyword);
                }
            }
        }
    }

}
