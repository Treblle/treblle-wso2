package com.treblle.wso2publisher.handlers;

import com.treblle.wso2publisher.dto.TrebllePayload;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


public class PublisherClientTest {

    @Test
    public void getRandomBaseUrl_ReturnsValidUrl() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        Method getRandomBaseUrlMethod = PublisherClient.class.getDeclaredMethod("getRandomBaseUrl");
        getRandomBaseUrlMethod.setAccessible(true);

        String url = (String) getRandomBaseUrlMethod.invoke(publisherClient);
        Assert.assertTrue(Arrays.asList("https://rocknrolla.treblle.com", "https://punisher.treblle.com", "https://sicario.treblle.com").contains(url));
    }

    @Test
    public void getRandomBaseUrl_MultipleCalls_ReturnsDifferentUrls() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        Method getRandomBaseUrlMethod = PublisherClient.class.getDeclaredMethod("getRandomBaseUrl");
        getRandomBaseUrlMethod.setAccessible(true);

        Set<String> urls = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            String url = (String) getRandomBaseUrlMethod.invoke(publisherClient);
            urls.add(url);
        }

        Assert.assertTrue(urls.size() > 1);
    }

    @Test
    public void getRandomBaseUrl_AlwaysReturnsNonNull() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        Method getRandomBaseUrlMethod = PublisherClient.class.getDeclaredMethod("getRandomBaseUrl");
        getRandomBaseUrlMethod.setAccessible(true);

        for (int i = 0; i < 10; i++) {
            String url = (String) getRandomBaseUrlMethod.invoke(publisherClient);
            Assert.assertNotNull(url);
        }
    }

    @Test
    public void maskKeywordInJson_MasksSingleKeyword() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        org.json.JSONObject jsonObject = new org.json.JSONObject("{\"password\":\"123456\"}");
        String keyword = "password";

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, String.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keyword);
        Assert.assertEquals("****", jsonObject.getString("password"));
    }

    @Test
    public void maskKeywordInJson_MasksNestedKeyword() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        org.json.JSONObject jsonObject = new org.json.JSONObject("{\"user\":{\"password\":\"123456\"}}");
        String keyword = "password";

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, String.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keyword);
        Assert.assertEquals("****", jsonObject.getJSONObject("user").getString("password"));
    }

    @Test
    public void maskKeywordInJson_DoesNotMaskNonMatchingKeyword() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        org.json.JSONObject jsonObject = new org.json.JSONObject("{\"username\":\"john_doe\"}");
        String keyword = "password";

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, String.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keyword);
        Assert.assertEquals("john_doe", jsonObject.getString("username"));
    }

    @Test
    public void maskKeywordInJson_HandlesEmptyJsonObject() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        org.json.JSONObject jsonObject = new org.json.JSONObject("{}");
        String keyword = "password";

        Method maskKeywordInJsonMethod = PublisherClient.class.getDeclaredMethod("maskKeywordInJson", org.json.JSONObject.class, String.class);
        maskKeywordInJsonMethod.setAccessible(true);
        maskKeywordInJsonMethod.invoke(publisherClient, jsonObject, keyword);
        Assert.assertTrue(jsonObject.length() == 0);
    }


    @Test
    public void doRetry_DropsEventWhenNoAttemptsLeft() throws Exception {

        PublisherClient publisherClient = new PublisherClient("abc123");
        TrebllePayload payload = new TrebllePayload();
        PublisherClientContextHolder.PUBLISH_ATTEMPTS.set(0);

        Method doRetryMethod = PublisherClient.class.getDeclaredMethod("doRetry", TrebllePayload.class);
        doRetryMethod.setAccessible(true);
        doRetryMethod.invoke(publisherClient, payload);
        Assert.assertEquals(0, (int) PublisherClientContextHolder.PUBLISH_ATTEMPTS.get());
    }

}