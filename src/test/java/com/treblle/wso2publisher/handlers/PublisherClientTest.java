package com.treblle.wso2publisher.handlers;

import com.treblle.wso2publisher.dto.Data;
import com.treblle.wso2publisher.dto.Language;
import com.treblle.wso2publisher.dto.OperatingSystem;
import com.treblle.wso2publisher.dto.Request;
import com.treblle.wso2publisher.dto.Response;
import com.treblle.wso2publisher.dto.Server;
import com.treblle.wso2publisher.dto.TrebllePayload;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class PublisherClientTest {

    @Test
    public void defaultUrl_IsIngress() throws Exception {
        java.lang.reflect.Field field = PublisherClient.class.getDeclaredField("DEFAULT_URL");
        field.setAccessible(true);
        String url = (String) field.get(null);
        Assert.assertEquals("https://ingress.treblle.com", url);
    }

    @Test
    public void maskKeywordInJson_MasksSingleKeyword() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123", "def456",
                DataHolder.getInstance().getHttpClient());
        org.json.JSONObject jsonObject = new org.json.JSONObject("{\"password\":\"123456\"}");
        Set<String> keywords = new HashSet<>(Arrays.asList("password"));

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, Set.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keywords);
        Assert.assertEquals("****", jsonObject.getString("password"));
    }

    @Test
    public void maskKeywordInJson_MasksNestedKeyword() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123", "def456",
                DataHolder.getInstance().getHttpClient());
        org.json.JSONObject jsonObject = new org.json.JSONObject("{\"user\":{\"password\":\"123456\"}}");
        Set<String> keywords = new HashSet<>(Arrays.asList("password"));

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, Set.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keywords);
        Assert.assertEquals("****", jsonObject.getJSONObject("user").getString("password"));
    }

    @Test
    public void maskKeywordInJson_DoesNotMaskNonMatchingKeyword() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123", "def456",
                DataHolder.getInstance().getHttpClient());
        org.json.JSONObject jsonObject = new org.json.JSONObject("{\"username\":\"john_doe\"}");
        Set<String> keywords = new HashSet<>(Arrays.asList("password"));

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, Set.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keywords);
        Assert.assertEquals("john_doe", jsonObject.getString("username"));
    }

    @Test
    public void maskKeywordInJson_HandlesEmptyJsonObject() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123", "def456",
                DataHolder.getInstance().getHttpClient());
        org.json.JSONObject jsonObject = new org.json.JSONObject("{}");
        Set<String> keywords = new HashSet<>(Arrays.asList("password"));

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, Set.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keywords);
        Assert.assertTrue(jsonObject.length() == 0);
    }

    @Test
    public void buildRequestBody_AppliesPerApiMaskKeywords() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123", "def456",
                DataHolder.getInstance().getHttpClient());

        // Build a minimal TrebllePayload with a body containing custom fields
        TrebllePayload payload = new TrebllePayload();
        payload.setSdkToken("abc123");
        payload.setApiKey("def456");

        Data data = new Data();

        Language language = new Language();
        language.setName("java");
        language.setVersion("11");
        data.setLanguage(language);

        Request request = new Request();
        request.setTimestamp("2025-01-01 00:00:00");
        request.setIp("127.0.0.1");
        request.setUserAgent("test");
        request.setMethod("POST");
        request.setUrl("https://example.com/api");
        request.setHeaders(new HashMap<>());

        // Set a request body with custom fields that should be masked by per-API keywords
        request.setBodyRaw("{\"account_number\":\"123456789\",\"transaction_id\":\"TXN-001\"}");
        data.setRequest(request);

        Response response = new Response();
        response.setCode(200);
        response.setSize(100L);
        response.setHeaders(new HashMap<>());
        response.setLoadTime(50.0);
        response.setBodyRaw("{\"balance\":\"5000\",\"dob\":\"1990-01-01\"}");
        data.setResponse(response);

        Server server = new Server();
        server.setIp("10.0.0.1");
        server.setTimezone("UTC");
        OperatingSystem os = new OperatingSystem();
        os.setName("Linux");
        os.setArchitecture("amd64");
        os.setRelease("5.0");
        server.setOs(os);
        data.setServer(server);

        data.setErrors(new ArrayList<>());
        payload.setData(data);

        // Set per-API mask keywords
        payload.setPerApiMaskKeywords(Arrays.asList("account_number", "dob"));

        Method buildMethod = PublisherClient.class.getDeclaredMethod("buildRequestBodyForTrebllePayload", TrebllePayload.class);
        buildMethod.setAccessible(true);
        org.json.JSONObject result = (org.json.JSONObject) buildMethod.invoke(publisherClient, payload);

        // Verify per-API keywords were masked
        org.json.JSONObject dataJson = result.getJSONObject("data");
        org.json.JSONObject requestJson = dataJson.getJSONObject("request");
        org.json.JSONObject reqBodyJson = requestJson.getJSONObject("body");
        Assert.assertEquals("****", reqBodyJson.getString("account_number"));
        Assert.assertEquals("TXN-001", reqBodyJson.getString("transaction_id")); // should NOT be masked

        org.json.JSONObject responseJson = dataJson.getJSONObject("response");
        org.json.JSONObject resBodyJson = responseJson.getJSONObject("body");
        Assert.assertEquals("****", resBodyJson.getString("dob"));
        Assert.assertEquals("5000", resBodyJson.getString("balance")); // should NOT be masked
    }
}