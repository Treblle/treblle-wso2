package com.treblle.wso2publisher.handlers;

import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2SynapseEnvironment;
import org.junit.Assert;
import org.junit.Test;

import com.treblle.wso2publisher.dto.TrebllePayload;
import com.treblle.wso2publisher.handlers.DataHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class APILogHandlerTest {

    @Test
    public void handleRequestTest() throws Exception {

        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        synCtx.setProperty("api.ut.api", "mock-v1");
        synCtx.setProperty("tenant.info.domain", "carbon.super");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-FORWARDED-FOR", "0:0:0:0:0:0:0:1");
        axisMsgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        axisMsgCtx.setProperty("REST_URL_POSTFIX", "/test");
        axisMsgCtx.setProperty("HTTP_METHOD", "POST");
        System.setProperty("TREBLLE_ENABLED_TENANT_DOMAINS", "carbon.super,abc.com");

        APILogHandler apiLogHandler = new APILogHandler();
        boolean response = apiLogHandler.handleRequest(synCtx);
        Assert.assertTrue(response);

        // Verify request data captured
        Assert.assertEquals(headers, synCtx.getProperty("TREBLLE_REQ_HEADERS"));
        // TREBLLE_REQ_BODY is a raw JSON String (may be null when no body is present)
        Object reqBody = synCtx.getProperty("TREBLLE_REQ_BODY");
        Assert.assertTrue(reqBody == null || reqBody instanceof String);
        Assert.assertEquals("/test", synCtx.getProperty("TREBLLE_REQ_PATH"));
        Assert.assertEquals("0:0:0:0:0:0:0:1", synCtx.getProperty("TREBLLE_REQ_IP"));
        Assert.assertEquals("POST", synCtx.getProperty("TREBLLE_REQ_METHOD"));

        // Verify API name captured (previously in handleRequestOutFlow)
        String apiName = (String) synCtx.getProperty("TREBLLE_API_NAME");
        Assert.assertEquals("mock-v1", apiName);
    }

    @Test
    public void testGetSourceIP() throws Exception {

        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-FORWARDED-FOR", "0:0:0:0:0:0:0:1");

        APILogHandler apiLogHandler = new APILogHandler();
        Method getSourceIPMethod = APILogHandler.class.getDeclaredMethod("getSourceIP",
                org.apache.axis2.context.MessageContext.class, Map.class);
        getSourceIPMethod.setAccessible(true);
        String sourceIP = (String) getSourceIPMethod.invoke(apiLogHandler, axisMsgCtx, headers);
        Assert.assertEquals(sourceIP, "0:0:0:0:0:0:0:1");
    }

    @Test
    public void testGetHeaders() throws Exception {
        // Set up the mock behavior
        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        axisMsgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        APILogHandler apiLogHandler = new APILogHandler();

        Method getHeadersMethod = APILogHandler.class.getDeclaredMethod("getHeaders", MessageContext.class);
        getHeadersMethod.setAccessible(true);

        // Invoke the private method
        @SuppressWarnings("unchecked")
        Map<String, String> resultHeaders = (Map<String, String>) getHeadersMethod.invoke(apiLogHandler, synCtx);

        // Assert the expected results
        Assert.assertNotNull(resultHeaders);
        Assert.assertEquals("application/json", resultHeaders.get("Content-Type"));
    }

    @Test
    public void testGetResponseTime() throws Exception {
        // Set up the mock behavior
        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        synCtx.setProperty("APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME",
                String.valueOf(System.currentTimeMillis()));
        Thread.sleep(1000);

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the getResponseTime method
        Method getResponseTimeMethod = APILogHandler.class.getDeclaredMethod("getResponseTime", MessageContext.class);
        getResponseTimeMethod.setAccessible(true);
        long responseTime = (long) getResponseTimeMethod.invoke(apiLogHandler, synCtx);

        // Assert the expected results
        assertNotNull(responseTime);
        Assert.assertTrue(responseTime >= 1000);
    }

    @Test
    public void testGetServerIpAddressWithCarbonIP() throws Exception {

        String ipAddress = "10.10.0.15";
        System.setProperty("carbon.local.ip", ipAddress);

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the getServerIpAddress method
        Method getServerIpAddressMethod = APILogHandler.class.getDeclaredMethod("getServerIpAddress");

        getServerIpAddressMethod.setAccessible(true);
        String serverIp = (String) getServerIpAddressMethod.invoke(apiLogHandler);

        // Assert the expected results
        Assert.assertEquals(ipAddress, serverIp);
    }

    @Test
    public void testCreatePayload() throws Exception {

        // Set up the mock behavior
        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        Thread.sleep(1000);

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the createPayload method
        Method createPayloadMethod = APILogHandler.class.getDeclaredMethod("createPayload", MessageContext.class,
                String.class);
        createPayloadMethod.setAccessible(true);
        TrebllePayload trebllePayload = (TrebllePayload) createPayloadMethod.invoke(apiLogHandler, synCtx, "test");

        // Assert the expected results
        assertNotNull(trebllePayload);
    }

    @Test
    public void testCreatePayloadWithEnrichedProperties() throws Exception {

        // Set up the mock behavior
        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));

        // Set enriched properties that would be available after APIAuthenticationHandler
        synCtx.setProperty("TREBLLE_TENANT_DOMAIN", "carbon.super");
        synCtx.setProperty("TREBLLE_APP_NAME", "TestApp");
        synCtx.setProperty("TREBLLE_APP_ID", "app-123");
        synCtx.setProperty("TREBLLE_USER_ID", "admin@carbon.super");
        synCtx.setProperty("TREBLLE_API_PUBLISHER", "admin");
        synCtx.setProperty("TREBLLE_API_UUID", "uuid-456");
        synCtx.setProperty("TREBLLE_API_NAME", "TestAPI");

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the createPayload method
        Method createPayloadMethod = APILogHandler.class.getDeclaredMethod("createPayload", MessageContext.class,
                String.class);
        createPayloadMethod.setAccessible(true);
        TrebllePayload trebllePayload = (TrebllePayload) createPayloadMethod.invoke(apiLogHandler, synCtx, "https://gateway.example.com");

        // Assert the expected results
        assertNotNull(trebllePayload);
        Assert.assertEquals("carbon.super", trebllePayload.getTenantId());
        Assert.assertEquals("TestApp", trebllePayload.getAppName());
        Assert.assertEquals("app-123", trebllePayload.getAppId());
        Assert.assertEquals("admin@carbon.super", trebllePayload.getUserId());
        Assert.assertEquals("admin", trebllePayload.getApiPublisher());
        Assert.assertEquals("uuid-456", trebllePayload.getInternalId());
        Assert.assertEquals("TestAPI", trebllePayload.getInternalName());
    }

    @Test
    public void testIsEnabledTenantDomain() throws Exception {

        // Set up the mock behavior
        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        synCtx.setProperty("tenant.info.domain", "");
        Thread.sleep(1000);

        System.setProperty("TREBLLE_ENABLED_TENANT_DOMAINS", "carbon.super,abc.com,xyz.com");
        DataHolder.getInstance().reloadEnabledTenantDomains();

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the isEnabledTenantDomain method
        Method isEnabledTenantDomainMethod = APILogHandler.class.getDeclaredMethod("isEnabledTenantDomain",
                MessageContext.class);
        isEnabledTenantDomainMethod.setAccessible(true);
        boolean isEnabledTenantDomain = (boolean) isEnabledTenantDomainMethod.invoke(apiLogHandler, synCtx);
        assertFalse(isEnabledTenantDomain);

        synCtx.setProperty("tenant.info.domain", "xyz.com");
        isEnabledTenantDomain = (boolean) isEnabledTenantDomainMethod.invoke(apiLogHandler, synCtx);
        assertTrue(isEnabledTenantDomain);

        synCtx.setProperty("tenant.info.domain", "pqr.com");
        isEnabledTenantDomain = (boolean) isEnabledTenantDomainMethod.invoke(apiLogHandler, synCtx);
        assertFalse(isEnabledTenantDomain);
    }

    @Test
    public void testHandleRequestCapturesEnrichedProperties() throws Exception {

        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));

        // Set up tenant domain and transport headers
        synCtx.setProperty("tenant.info.domain", "carbon.super");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        axisMsgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        axisMsgCtx.setProperty("HTTP_METHOD", "GET");
        axisMsgCtx.setProperty("REST_URL_POSTFIX", "/api/test");

        // Set enriched properties that auth handler would populate
        synCtx.setProperty("APPLICATION_NAME", "MyApp");
        synCtx.setProperty("APPLICATION_ID", "42");
        synCtx.setProperty("END_USER_NAME", "testuser@carbon.super");
        synCtx.setProperty("API_PUBLISHER", "apiadmin");

        System.setProperty("TREBLLE_ENABLED_TENANT_DOMAINS", "carbon.super");

        APILogHandler apiLogHandler = new APILogHandler();
        boolean result = apiLogHandler.handleRequest(synCtx);
        Assert.assertTrue(result);

        // Verify enriched properties were captured
        Assert.assertEquals("carbon.super", synCtx.getProperty("TREBLLE_TENANT_DOMAIN"));
        Assert.assertEquals("MyApp", synCtx.getProperty("TREBLLE_APP_NAME"));
        Assert.assertEquals("42", synCtx.getProperty("TREBLLE_APP_ID"));
        Assert.assertEquals("testuser@carbon.super", synCtx.getProperty("TREBLLE_USER_ID"));
        Assert.assertEquals("apiadmin", synCtx.getProperty("TREBLLE_API_PUBLISHER"));
    }

    @Test
    public void testPerApiMaskKeywordsFromMessageContext() throws Exception {

        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));

        // Set up required properties
        synCtx.setProperty("tenant.info.domain", "carbon.super");
        synCtx.setProperty("API_UUID", "test-uuid-123");
        synCtx.setProperty("treblle_mask_keywords", "ssn,dob,account_number");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        axisMsgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        axisMsgCtx.setProperty("HTTP_METHOD", "GET");
        axisMsgCtx.setProperty("REST_URL_POSTFIX", "/api/test");

        System.setProperty("TREBLLE_ENABLED_TENANT_DOMAINS", "carbon.super");

        // Clear the cache before test
        Field cacheField = APILogHandler.class.getDeclaredField("apiConfigCache");
        cacheField.setAccessible(true);
        ((APILogHandler.PerApiConfigCache) cacheField.get(null)).invalidateAll();

        APILogHandler apiLogHandler = new APILogHandler();
        boolean result = apiLogHandler.handleRequest(synCtx);
        Assert.assertTrue(result);

        // Verify per-API mask keywords were captured
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) synCtx.getProperty("TREBLLE_PER_API_MASK_KEYWORDS");
        Assert.assertNotNull(keywords);
        Assert.assertEquals(3, keywords.size());
        Assert.assertTrue(keywords.contains("ssn"));
        Assert.assertTrue(keywords.contains("dob"));
        Assert.assertTrue(keywords.contains("account_number"));
    }

    @Test
    public void testPerApiMaskKeywordsCaching() throws Exception {

        // Clear the cache before test
        Field cacheField = APILogHandler.class.getDeclaredField("apiConfigCache");
        cacheField.setAccessible(true);
        APILogHandler.PerApiConfigCache cache = (APILogHandler.PerApiConfigCache) cacheField.get(null);
        cache.invalidateAll();

        SynapseConfiguration synCfg = new SynapseConfiguration();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);

        // First request: set the keyword property
        org.apache.axis2.context.MessageContext axisMsgCtx1 = new org.apache.axis2.context.MessageContext();
        MessageContext synCtx1 = new Axis2MessageContext(axisMsgCtx1, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        synCtx1.setProperty("tenant.info.domain", "carbon.super");
        synCtx1.setProperty("API_UUID", "cache-test-uuid");
        synCtx1.setProperty("treblle_mask_keywords", "field_a,field_b");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        axisMsgCtx1.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        axisMsgCtx1.setProperty("HTTP_METHOD", "GET");
        axisMsgCtx1.setProperty("REST_URL_POSTFIX", "/api/test");

        System.setProperty("TREBLLE_ENABLED_TENANT_DOMAINS", "carbon.super");

        APILogHandler apiLogHandler = new APILogHandler();
        apiLogHandler.handleRequest(synCtx1);

        // Verify cache was populated
        Assert.assertNotNull(cache.getIfPresent("cache-test-uuid"));

        // Second request: same API UUID but WITHOUT the property — should use cache
        org.apache.axis2.context.MessageContext axisMsgCtx2 = new org.apache.axis2.context.MessageContext();
        MessageContext synCtx2 = new Axis2MessageContext(axisMsgCtx2, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        synCtx2.setProperty("tenant.info.domain", "carbon.super");
        synCtx2.setProperty("API_UUID", "cache-test-uuid");
        // Note: NO treblle_mask_keywords property set

        axisMsgCtx2.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        axisMsgCtx2.setProperty("HTTP_METHOD", "GET");
        axisMsgCtx2.setProperty("REST_URL_POSTFIX", "/api/test");

        apiLogHandler.handleRequest(synCtx2);

        // Verify cached keywords were used
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) synCtx2.getProperty("TREBLLE_PER_API_MASK_KEYWORDS");
        Assert.assertNotNull(keywords);
        Assert.assertEquals(2, keywords.size());
        Assert.assertTrue(keywords.contains("field_a"));
        Assert.assertTrue(keywords.contains("field_b"));
    }

    @Test
    public void testIsTextBasedContentType() throws Exception {
        APILogHandler apiLogHandler = new APILogHandler();
        Method method = APILogHandler.class.getDeclaredMethod("isTextBasedContentType", String.class);
        method.setAccessible(true);

        // Text-based types should be captured
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "application/json"));
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "application/json; charset=utf-8"));
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "application/xml"));
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "text/plain"));
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "text/html"));
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "application/x-www-form-urlencoded"));
        Assert.assertTrue((boolean) method.invoke(apiLogHandler, "multipart/form-data; boundary=x"));

        // Binary types must NOT be captured (so the pass-through stream is left untouched)
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "image/png"));
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "image/jpeg"));
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "application/pdf"));
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "application/octet-stream"));
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "audio/mpeg"));
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "video/mp4"));
        Assert.assertFalse((boolean) method.invoke(apiLogHandler, "font/woff2"));
    }

    @Test
    public void testGetMessageBodyRawSkipsBinaryContent() throws Exception {

        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));

        // Place a readable JSON payload on the message. If getMessageBodyRaw were to build/read
        // the body for a binary content type, it would return this string. The guard must
        // short-circuit BEFORE touching the stream, so it returns null for image/png.
        org.apache.synapse.commons.json.JsonUtil.getNewJsonPayload(
                axisMsgCtx, "{\"would_be_read\":true}", true, true);

        APILogHandler apiLogHandler = new APILogHandler();
        Method method = APILogHandler.class.getDeclaredMethod("getMessageBodyRaw", MessageContext.class, Map.class);
        method.setAccessible(true);

        // Binary response: must skip capture entirely -> null
        Map<String, String> pngHeaders = new HashMap<>();
        pngHeaders.put("Content-Type", "image/png");
        Object pngBody = method.invoke(apiLogHandler, synCtx, pngHeaders);
        assertNull("Binary (image/png) body must not be captured", pngBody);

        // Text response with the same payload present: should be captured normally
        Map<String, String> jsonHeaders = new HashMap<>();
        jsonHeaders.put("Content-Type", "application/json");
        Object jsonBody = method.invoke(apiLogHandler, synCtx, jsonHeaders);
        assertNotNull("JSON body should be captured", jsonBody);
        Assert.assertTrue(((String) jsonBody).contains("would_be_read"));
    }

    @Test
    public void testPerApiMaskKeywordsOnPayload() throws Exception {

        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));

        // Set per-API mask keywords on the MessageContext (as handleRequest would)
        List<String> keywords = java.util.Arrays.asList("custom_field", "sensitive_data");
        synCtx.setProperty("TREBLLE_PER_API_MASK_KEYWORDS", keywords);

        APILogHandler apiLogHandler = new APILogHandler();

        Method createPayloadMethod = APILogHandler.class.getDeclaredMethod("createPayload", MessageContext.class,
                String.class);
        createPayloadMethod.setAccessible(true);
        TrebllePayload payload = (TrebllePayload) createPayloadMethod.invoke(apiLogHandler, synCtx, "https://gateway.example.com");

        Assert.assertNotNull(payload);
        Assert.assertNotNull(payload.getPerApiMaskKeywords());
        Assert.assertEquals(2, payload.getPerApiMaskKeywords().size());
        Assert.assertTrue(payload.getPerApiMaskKeywords().contains("custom_field"));
        Assert.assertTrue(payload.getPerApiMaskKeywords().contains("sensitive_data"));
    }
}
