package com.treblle.wso2publisher.handlers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.AbstractSynapseHandler;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treblle.wso2publisher.dto.Data;
import com.treblle.wso2publisher.dto.Language;
import com.treblle.wso2publisher.dto.OperatingSystem;
import com.treblle.wso2publisher.dto.Request;
import com.treblle.wso2publisher.dto.Response;
import com.treblle.wso2publisher.dto.RuntimeError;
import com.treblle.wso2publisher.dto.Server;
import com.treblle.wso2publisher.dto.TrebllePayload;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class APILogHandler extends AbstractSynapseHandler {

    private static final String HEADER_X_FORWARDED_FOR = "X-FORWARDED-FOR";
    private static final String TREBLLE_REQ_HEADERS = "TREBLLE_REQ_HEADERS";
    private static final String TREBLLE_REQ_BODY = "TREBLLE_REQ_BODY";
    private static final String TREBLLE_REQ_PATH = "TREBLLE_REQ_PATH";
    private static final String TREBLLE_REQ_METHOD = "TREBLLE_REQ_METHOD";
    private static final String TREBLLE_API_NAME = "TREBLLE_API_NAME";
    private static final String TREBLLE_REQ_IP = "TREBLLE_REQ_IP";
    private static final String TREBLLE_ROUTE_PATH = "TREBLLE_ROUTE_PATH";
    private static final String TREBLLE_API_UUID = "TREBLLE_API_UUID";
    private static final String REST_URL_POSTFIX = "REST_URL_POSTFIX";
    private static final String HTTP_METHOD = "HTTP_METHOD";
    private static final String SYNAPSE_REST_API = "SYNAPSE_REST_API";
    private static final String API_ELECTED_RESOURCE = "API_ELECTED_RESOURCE";
    private static final String CARBON_LOCAL_IP = "carbon.local.ip";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static String serverIP;

    private static final Log log = LogFactory.getLog(APILogHandler.class);

    @Override
    public boolean handleRequestInFlow(MessageContext messageContext) {
        try {
            if (!isEnabledTenantDomain(messageContext)) {
                return true;
            }

            // Get the Axis2 message context from the Synapse message context
            org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                    .getAxis2MessageContext();

            // Retrieve and set request headers
            Map<String, String> headersMap = getHeaders(messageContext);
            messageContext.setProperty(TREBLLE_REQ_HEADERS, headersMap);

            // Retrieve and set the request body
            JsonNode jsonNode = getMessageBody(messageContext);
            messageContext.setProperty(TREBLLE_REQ_BODY, jsonNode);

            // Retrieve and set the request path
            String reqPath = (String) axis2MsgContext.getProperty(REST_URL_POSTFIX);
            messageContext.setProperty(TREBLLE_REQ_PATH, reqPath);

            // Retrieve and set the source IP address
            String sourceIP = getSourceIP(axis2MsgContext, headersMap);
            messageContext.setProperty(TREBLLE_REQ_IP, sourceIP);

            // Retrieve and set the HTTP method
            String apiMethod = (String) axis2MsgContext.getProperty(HTTP_METHOD);
            messageContext.setProperty(TREBLLE_REQ_METHOD, apiMethod);

            // Retrieve and set the API resource route path template
            String routePath = getRoutePath(messageContext);
            messageContext.setProperty(TREBLLE_ROUTE_PATH, routePath);

            if (log.isDebugEnabled()) {
                log.debug("Captured route path: " + (routePath != null ? routePath : "NULL"));
            }

            return true;
        } catch (Exception e) {
            log.error("Treblle handler failed during request inflow. Continuing request processing.", e);
            return true; // Always return true to not block the request
        }
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext messageContext) {
        try {
            // Retrieve and set the API name
            String apiName = getApiName(messageContext);
            messageContext.setProperty(TREBLLE_API_NAME, apiName);

            if (log.isDebugEnabled()) {
                log.debug("Captured API name: " + (apiName != null ? apiName : "NULL"));
            }

            // Retrieve and set the API UUID
            String apiUuid = getApiUuid(messageContext);
            messageContext.setProperty(TREBLLE_API_UUID, apiUuid);

            if (log.isDebugEnabled()) {
                log.debug("Captured API UUID: " + (apiUuid != null ? apiUuid : "NULL"));
            }

            return true;
        } catch (Exception e) {
            log.error("Treblle handler failed during request outflow. Continuing request processing.", e);
            return true; // Always return true to not block the request
        }
    }

    @Override
    public boolean handleResponseInFlow(MessageContext messageContext) {
        return true;
    }

    @Override
    public boolean handleResponseOutFlow(MessageContext messageContext) {
        try {
            if (!isEnabledTenantDomain(messageContext)) {
                return true;
            }

            // Create a TrebllePayload object using the message context and gateway URL
            TrebllePayload payload = createPayload(messageContext, DataHolder.getInstance().getGatewayURL());
            // Add the payload to the event queue for processing
            DataHolder.getInstance().getEventQueue().put(payload);
            return true;
        } catch (Exception e) {
            log.error("Treblle handler failed during response outflow. Continuing request processing.", e);
            return true; // Always return true to not block the request
        }
    }

    private long getResponseTime(org.apache.synapse.MessageContext messageContext) {
        // Initialize the response time to 0
        long responseTime = 0;
        try {
            long rtStartTime = 0;
            // Check if the request execution start time is available in the message context
            if (messageContext.getProperty(APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME) != null) {
                Object objRtStartTime = messageContext.getProperty(APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME);
                // Parse the start time from the message context property
                rtStartTime = (objRtStartTime == null ? 0 : Long.parseLong((String) objRtStartTime));
            }
            // Calculate the response time by subtracting the start time from the current
            // time
            responseTime = System.currentTimeMillis() - rtStartTime;
            responseTime = responseTime * 1000;
        } catch (Exception e) {
            // Log any errors that occur during the calculation of the response time
            log.error("Error getResponseTime -  " + e.getMessage(), e);
        }
        return responseTime;
    }

    private String getSourceIP(org.apache.axis2.context.MessageContext axis2Context, Map<String, String> headers) {
        String clientIP;
        // Check if the X-FORWARDED-FOR header is present in the headers map
        String xForwardedForHeader = (String) headers.get(HEADER_X_FORWARDED_FOR);
        if (!StringUtils.isEmpty(xForwardedForHeader)) {
            // Use the first IP address in the X-FORWARDED-FOR header
            clientIP = xForwardedForHeader;
            int index = xForwardedForHeader.indexOf(',');
            if (index > -1) {
                clientIP = clientIP.substring(0, index);
            }
        } else {
            // Fallback to the remote address property from the Axis2 message context
            clientIP = (String) axis2Context.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);
        }
        // Return null if the client IP is empty
        if (StringUtils.isEmpty(clientIP)) {
            return null;
        }
        // Ignore the port if present and only use the IP address
        if (clientIP.contains(":") && clientIP.split(":").length == 2) {
            log.debug("Port will be ignored and only the IP address will be picked from " + clientIP);
            clientIP = clientIP.split(":")[0];
        }

        return clientIP;
    }

    private TrebllePayload createPayload(org.apache.synapse.MessageContext messageContext, String gatewayURL) {

        // Retrieve the Axis2 message context from the Synapse message context
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();
        // Retrieve and handle request headers
        Map<String, String> reqHeaders = (Map<String, String>) messageContext.getProperty(TREBLLE_REQ_HEADERS);
        if (reqHeaders == null) {
            log.error("Request headers are null. Setting a default value.");
            reqHeaders = new HashMap<String, String>();
        }
        // Retrieve the request body
        JsonNode reqBody = (JsonNode) messageContext.getProperty(TREBLLE_REQ_BODY);

        // Create and initialize the Language object
        final Language language = new Language();
        language.setName("java");
        language.setVersion(System.getProperty("java.version"));

        // Create and initialize the OperatingSystem object
        final OperatingSystem os = new OperatingSystem();
        os.setName(System.getProperty("os.name"));
        os.setArchitecture(System.getProperty("os.arch"));
        os.setRelease(System.getProperty("os.version"));

        // Create and initialize the Server object
        final Server server = new Server();
        server.setIp(getServerIpAddress());
        server.setTimezone(TimeZone.getDefault().getID());
        server.setOs(os);

        server.setSoftware("WSO2 v4.3");
        server.setSignature("");
        server.setProtocol("HTTP");
        server.setEncoding(Charset.defaultCharset().name());

        // Create and initialize the Request object
        final Request request = new Request();
        request.setTimestamp(ZonedDateTime.now(ZoneOffset.UTC).format(DATE_TIME_FORMATTER));

        String reqIp = (String) messageContext.getProperty(TREBLLE_REQ_IP);
        if (reqIp == null) {
            log.warn("Request IP is null. Setting a default value.");
            reqIp = "127.0.0.1";
        }
        request.setIp(reqIp);

        String userAgent = (String) reqHeaders.get("User-Agent");
        if (userAgent == null) {
            log.warn("User-Agent header is null. Setting a default value.");
            userAgent = "";
        }
        request.setUserAgent(userAgent);

        String method = (String) messageContext.getProperty(TREBLLE_REQ_METHOD);
        if (method == null) {
            log.warn("Request method is null. Setting a default value.");
            method = "GET";
        }
        request.setMethod(method);

        String reqURL = (String) messageContext.getProperty(TREBLLE_REQ_PATH);
        if (reqURL == null) {
            log.warn("Request URL is null. Setting a default value.");
            reqURL = "/";
        }
        if (!reqURL.startsWith("/")) {
            reqURL = "/" + reqURL;
        }
        String reqPath = gatewayURL + reqURL;
        request.setUrl(reqPath);
        request.setHeaders(reqHeaders);
        request.setBody(reqBody);

        // Set the route path (API resource template pattern)
        String routePath = (String) messageContext.getProperty(TREBLLE_ROUTE_PATH);
        request.setRoutePath(routePath);

        // Create and initialize the Response object
        final Data data = new Data();
        final Response response = new Response();

        int responseCode = 500;
        Object object = axis2MsgContext.getProperty("HTTP_SC");
        if (object instanceof Integer) {
            responseCode = (int) object;
        } else if (object instanceof String) {
            responseCode = Integer.parseInt((String) object);
        }

        // Handle response errors
        if (responseCode >= 400 && responseCode < 600) {

            final RuntimeError runtimeError = new RuntimeError();

            // check errors for 404
            Object errorMessageObj = messageContext.getProperty("ERROR_MESSAGE");
            String errorType = errorMessageObj instanceof String ? (String) errorMessageObj : null;

            Object errorDetailObj = messageContext.getProperty("ERROR_DETAIL");
            String errorDetail = errorDetailObj instanceof String ? (String) errorDetailObj : null;

            runtimeError.setType(errorType);
            runtimeError.setMessage(errorDetail);
            runtimeError.setSource("onError");

            List<RuntimeError> runtimeErrors = new ArrayList<>(2);
            runtimeErrors.add(runtimeError);
            data.setErrors(runtimeErrors);
        }

        // Set response properties
        response.setCode(responseCode);
        JsonNode jsonNode = getMessageBody(messageContext);
        response.setBody(jsonNode);

        if (jsonNode != null) {
            String jsonString = jsonNode.toString();
            byte[] responseBody = jsonString.getBytes();
            response.setSize((long) responseBody.length);
        } else {
            response.setSize(0L);
        }

        Map<String, String> responseHeaderMap = getHeaders(messageContext);
        response.setHeaders(responseHeaderMap);
        response.setLoadTime((double) getResponseTime(messageContext));

        // Create and initialize the Data object
        data.setServer(server);
        data.setLanguage(language);
        data.setRequest(request);
        data.setResponse(response);

        // Create and initialize the TrebllePayload object
        TrebllePayload payload = new TrebllePayload();
        payload.setData(data);

        // Set the API UUID (internal_id) at root level
        String apiUuid = (String) messageContext.getProperty(TREBLLE_API_UUID);
        payload.setInternalId(apiUuid);

        // Set the API name (internal_name) at root level
        String apiName = (String) messageContext.getProperty(TREBLLE_API_NAME);
        payload.setInternalName(apiName);

        return payload;
    }

    private Map<String, String> getHeaders(MessageContext messageContext) {

        // Retrieve the Axis2 message context from the Synapse message context
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();
        // Retrieve transport headers from the Axis2 message context
        Map headers = (Map) axis2MsgContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        // Create a map to store the headers as key-value pairs
        Map<String, String> headersMap = new HashMap<>();

        if (headers == null) {
            log.debug("Transport headers are null.");
            return headersMap;
        }
        // Populate the headers map with the transport headers
        for (Object key : headers.keySet()) {
            headersMap.put(key.toString(), headers.get(key).toString());
        }
        return headersMap;
    }

    private JsonNode getMessageBody(MessageContext messageContext) {

        // Retrieve the Axis2 message context from the Synapse message context
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        // Initialize the JsonNode object to null
        JsonNode jsonNode = null;

        // Check if the Content-Type is application/json
        Map<String, String> headers = getHeaders(messageContext);
        if (!headers.containsKey("Content-Type") || !headers.get("Content-Type").contains("application/json")) {
            log.debug("Content-Type is not application/json. Hence skipping the message body.");
            return null;
        }
        try {
            // Build the message to ensure the payload is available
            RelayUtils.buildMessage(axis2MsgContext);
        } catch (Exception e) {
            log.error(e.getMessage());
            return null;
        }

        // Convert the JSON payload to a string
        String jsonPayloadToString = JsonUtil.jsonPayloadToString(axis2MsgContext);
        if (jsonPayloadToString == null) {
            log.error("JSON payload is null");
            return null;
        }
        // Parse the JSON string into a JsonNode using shared ObjectMapper
        try {
            jsonNode = OBJECT_MAPPER.readTree(jsonPayloadToString);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
            return null;
        }

        return jsonNode;
    }

    private String getServerIpAddress() {

        // Check if the server IP is already set
        if (serverIP != null) {
            return serverIP;
        }

        // Retrieve the server IP from system properties
        serverIP = System.getProperty(CARBON_LOCAL_IP);
        if (serverIP != null) {
            return serverIP;
        }

        try {
            // Retrieve the server IP using InetAddress
            InetAddress inetAddress = InetAddress.getLocalHost();
            serverIP = inetAddress.getHostAddress();
        } catch (UnknownHostException e) {
            // Handle the UnknownHostException and set a default IP
            log.error("Unknown host exception: " + e.getMessage());
            serverIP = "127.0.0.1";
        }

        return serverIP;
    }

     private boolean isEnabledTenantDomain(MessageContext messageContext) {

        // Retrieve the tenant domain from the message context
        String tenantDomain = (String) messageContext.getProperty("tenant.info.domain");

        if (tenantDomain == null) {
            log.warn("Tenant domain is null. Skipping the handler.");
            return false;
        }

        if (DataHolder.getInstance().getEnabledTenantDomains().containsKey(tenantDomain)) {
            return true;
        }

        return false;
    }

    /**
     * Get the API resource route path template by trying multiple MessageContext properties.
     * This method attempts several different property names to maximize compatibility across
     * different WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     * @return the route path template (e.g., "/users/{userId}/posts") or null if not found
     */
    private String getRoutePath(MessageContext messageContext) {
        // List of property names to try, in order of preference
        String[] propertyNames = {
            "API_ELECTED_RESOURCE",      // Primary property for API resource template
            "REST_URL_PATTERN",           // Alternative property name
            "API_RESOURCE_CACHE_KEY",     // Cache key that may contain resource info
            "api.ut.resource",            // URI template resource property
            "SYNAPSE_REST_API_RESOURCE",  // Synapse REST API resource
            "REST_SUB_REQUEST_PATH",      // Sub-request path (may be template)
            "API_RESOURCE_PATTERN"        // Resource pattern property
        };

        log.info("Treblle: Attempting to retrieve route path template...");

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                String routePath = (String) messageContext.getProperty(propertyName);
                if (routePath != null && !routePath.isEmpty()) {
                    log.info("Treblle: Found route path using property '" + propertyName + "': " + routePath);
                    return routePath;
                } else {
                    log.debug("Treblle: Property '" + propertyName + "' is " +
                        (routePath == null ? "null" : "empty"));
                }
            } catch (Exception e) {
                log.warn("Treblle: Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }

        // If all direct property lookups fail, log available properties for debugging
        if (log.isInfoEnabled()) {
            logAvailableProperties(messageContext);
        }

        log.warn("Treblle: Unable to determine route path template. The 'route_path' field will be null.");
        return null;
    }

    /**
     * Get the API name by trying multiple MessageContext properties.
     * This method attempts several different property names to maximize compatibility across
     * different WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     * @return the API name or null if not found
     */
    private String getApiName(MessageContext messageContext) {
        // List of property names to try, in order of preference
        String[] propertyNames = {
            "SYNAPSE_REST_API",        // Primary property for API name
            "API_NAME",                // Alternative property name
            "REST_API_NAME",           // REST API name property
            "api.name",                // Lowercase variant
            "REST_API_CONTEXT",        // API context (may contain name)
            "API_CONTEXT",             // Alternative context property
            "org.wso2.carbon.apimgt.gateway.handlers.api.name"  // Fully qualified property
        };

        log.info("Treblle: Attempting to retrieve API name...");

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                Object propertyValue = messageContext.getProperty(propertyName);
                if (propertyValue != null) {
                    String apiName = propertyValue.toString();
                    if (!apiName.isEmpty()) {
                        log.info("Treblle: Found API name using property '" + propertyName + "': " + apiName);
                        return apiName;
                    } else {
                        log.debug("Treblle: Property '" + propertyName + "' is empty");
                    }
                } else {
                    log.debug("Treblle: Property '" + propertyName + "' is null");
                }
            } catch (Exception e) {
                log.warn("Treblle: Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }

        // If all direct property lookups fail, log available properties for debugging
        if (log.isInfoEnabled()) {
            logAvailablePropertiesForName(messageContext);
        }

        log.warn("Treblle: Unable to determine API name. The 'internal_name' field will be null.");
        return null;
    }

    /**
     * Get the API UUID by trying multiple MessageContext properties.
     * This method attempts several different property names to maximize compatibility across
     * different WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     * @return the API UUID or null if not found
     */
    private String getApiUuid(MessageContext messageContext) {
        // List of property names to try, in order of preference
        String[] propertyNames = {
            "API_UUID",                // Primary property for API UUID
            "api.uuid",                // Alternative property name
            "__api.uuid",              // Internal property with double underscore prefix
            "API_IDENTIFIER",          // API Identifier object (may contain UUID)
            "ELECTED_API_UUID",        // Elected API UUID
            "org.wso2.carbon.apimgt.gateway.handlers.api.uuid",  // Fully qualified property
            "apiUUID"                  // CamelCase variant
        };

        log.info("Treblle: Attempting to retrieve API UUID...");

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                Object propertyValue = messageContext.getProperty(propertyName);
                if (propertyValue != null) {
                    String apiUuid = propertyValue.toString();
                    if (!apiUuid.isEmpty()) {
                        log.info("Treblle: Found API UUID using property '" + propertyName + "': " + apiUuid);
                        return apiUuid;
                    } else {
                        log.debug("Treblle: Property '" + propertyName + "' is empty");
                    }
                } else {
                    log.debug("Treblle: Property '" + propertyName + "' is null");
                }
            } catch (Exception e) {
                log.warn("Treblle: Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }

        // If all direct property lookups fail, log available properties for debugging
        if (log.isInfoEnabled()) {
            logAvailablePropertiesForUuid(messageContext);
        }

        log.warn("Treblle: Unable to determine API UUID. The 'internal_id' field will be null.");
        return null;
    }

    /**
     * Log all available properties in MessageContext that might contain API name information.
     * This is useful for debugging and discovering which properties are available in different
     * WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     */
    private void logAvailablePropertiesForName(MessageContext messageContext) {
        log.info("Treblle: Listing all MessageContext properties containing 'NAME', 'API', or 'CONTEXT':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("NAME") || upperKey.contains("API") ||
                    upperKey.contains("CONTEXT")) {

                    Object value = messageContext.getProperty(key);
                    log.info("Treblle:   - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.info("Treblle:   (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.info("Treblle: Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("NAME") || upperKey.contains("API") ||
                    upperKey.contains("CONTEXT")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.info("Treblle:   - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.info("Treblle:   (No relevant properties found in Axis2 MessageContext)");
            }

        } catch (Exception e) {
            log.error("Treblle: Error logging available properties for API name: " + e.getMessage(), e);
        }
    }

    /**
     * Log all available properties in MessageContext that might contain API UUID information.
     * This is useful for debugging and discovering which properties are available in different
     * WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     */
    private void logAvailablePropertiesForUuid(MessageContext messageContext) {
        log.info("Treblle: Listing all MessageContext properties containing 'UUID', 'API', or 'IDENTIFIER':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("UUID") || upperKey.contains("API") ||
                    upperKey.contains("IDENTIFIER") || upperKey.contains("ID")) {

                    Object value = messageContext.getProperty(key);
                    log.info("Treblle:   - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.info("Treblle:   (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.info("Treblle: Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("UUID") || upperKey.contains("API") ||
                    upperKey.contains("IDENTIFIER") || upperKey.contains("ID")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.info("Treblle:   - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.info("Treblle:   (No relevant properties found in Axis2 MessageContext)");
            }

        } catch (Exception e) {
            log.error("Treblle: Error logging available properties for UUID: " + e.getMessage(), e);
        }
    }

    /**
     * Log all available properties in MessageContext that might contain route path information.
     * This is useful for debugging and discovering which properties are available in different
     * WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     */
    private void logAvailableProperties(MessageContext messageContext) {
        log.info("Treblle: Listing all MessageContext properties containing 'REST', 'API', 'RESOURCE', or 'PATTERN':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("REST") || upperKey.contains("API") ||
                    upperKey.contains("RESOURCE") || upperKey.contains("PATTERN") ||
                    upperKey.contains("URI") || upperKey.contains("TEMPLATE")) {

                    Object value = messageContext.getProperty(key);
                    log.info("Treblle:   - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.info("Treblle:   (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.info("Treblle: Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("REST") || upperKey.contains("API") ||
                    upperKey.contains("RESOURCE") || upperKey.contains("PATTERN") ||
                    upperKey.contains("URI") || upperKey.contains("TEMPLATE")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.info("Treblle:   - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.info("Treblle:   (No relevant properties found in Axis2 MessageContext)");
            }

        } catch (Exception e) {
            log.error("Treblle: Error logging available properties: " + e.getMessage(), e);
        }
    }

}
