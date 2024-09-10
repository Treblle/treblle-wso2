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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class APILogHandlerTest {

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
    public void testHandleRequestInFlow() throws Exception {
        // Set up the mock behavior
        SynapseConfiguration synCfg = new SynapseConfiguration();
        org.apache.axis2.context.MessageContext axisMsgCtx = new org.apache.axis2.context.MessageContext();
        AxisConfiguration axisConfig = new AxisConfiguration();
        ConfigurationContext cfgCtx = new ConfigurationContext(axisConfig);
        MessageContext synCtx = new Axis2MessageContext(axisMsgCtx, synCfg,
                new Axis2SynapseEnvironment(cfgCtx, synCfg));
        synCtx.setProperty("tenant.info.domain", "carbon.super");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-FORWARDED-FOR", "0:0:0:0:0:0:0:1");
        axisMsgCtx.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        axisMsgCtx.setProperty("REST_URL_POSTFIX", "/test");
        axisMsgCtx.setProperty("HTTP_METHOD", "POST");
        System.setProperty("TREBLLE_ENABLED_TENANT_DOMAINS", "carbon.super,abc.com");

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the handleRequestInFlow method
        boolean result = apiLogHandler.handleRequestInFlow(synCtx);

        // Assert the expected results
        Assert.assertTrue(result);
        Assert.assertEquals(headers, synCtx.getProperty("TREBLLE_REQ_HEADERS"));
        Assert.assertNotNull(synCtx.getProperty("TREBLLE_REQ_BODY"));
        Assert.assertEquals("/test", synCtx.getProperty("TREBLLE_REQ_PATH"));
        Assert.assertEquals("0:0:0:0:0:0:0:1", synCtx.getProperty("TREBLLE_REQ_IP"));
        Assert.assertEquals("POST", synCtx.getProperty("TREBLLE_REQ_METHOD"));
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

        // Create the APILogHandler instance
        APILogHandler apiLogHandler = new APILogHandler();

        // Invoke the createPayload method
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

}
