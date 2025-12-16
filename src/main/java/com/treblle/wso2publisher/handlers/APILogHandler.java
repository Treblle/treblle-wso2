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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class APILogHandler extends AbstractSynapseHandler {

    private static final String HEADER_X_FORWARDED_FOR = "X-FORWARDED-FOR";
    private static final String TREBLLE_REQ_HEADERS = "TREBLLE_REQ_HEADERS";
    private static final String TREBLLE_REQ_BODY = "TREBLLE_REQ_BODY";
    private static final String TREBLLE_REQ_PATH = "TREBLLE_REQ_PATH";
    private static final String TREBLLE_REQ_METHOD = "TREBLLE_REQ_METHOD";
    private static final String TREBLLE_API_NAME = "TREBLLE_API_NAME";
    private static final String TREBLLE_REQ_IP = "TREBLLE_REQ_IP";
    private static final String TREBLLE_REQUEST_ID = "TREBLLE_REQUEST_ID";
    private static final String REST_URL_POSTFIX = "REST_URL_POSTFIX";
    private static final String HTTP_METHOD = "HTTP_METHOD";
    private static final String SYNAPSE_REST_API = "SYNAPSE_REST_API";
    private static final String CARBON_LOCAL_IP = "carbon.local.ip";
    private static final String API_UUID = "api.uuid";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static String serverIP;

    // Track processed requests to prevent duplicates (TTL: 60 seconds)
    private static final ConcurrentHashMap<String, Long> processedRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TTL_MS = TimeUnit.SECONDS.toMillis(60);

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

            // Generate a unique request ID to prevent duplicates
            String requestId = UUID.randomUUID().toString();
            messageContext.setProperty(TREBLLE_REQUEST_ID, requestId);

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

        } catch (Exception e) {
            // Never let Treblle SDK errors affect user requests
            log.error("Error in Treblle handleRequestInFlow: " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext messageContext) {
        // Retrieve the API name from the message context
        String apiName = (String) messageContext.getProperty(SYNAPSE_REST_API);
        // Set the API name in the message context with a unique property key
        messageContext.setProperty(TREBLLE_API_NAME, apiName);

        return true;
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

            // Get the request ID to prevent duplicates
            String requestId = (String) messageContext.getProperty(TREBLLE_REQUEST_ID);
            if (requestId == null) {
                log.warn("Treblle request ID not found. Generating new one.");
                requestId = UUID.randomUUID().toString();
            }

            // Check if this request has already been processed
            if (isDuplicateRequest(requestId)) {
                log.debug("Duplicate request detected and skipped: " + requestId);
                return true;
            }

            // Mark this request as processed
            markRequestAsProcessed(requestId);

            // Create a TrebllePayload object using the message context and gateway URL
            TrebllePayload payload = createPayload(messageContext, DataHolder.getInstance().getGatewayURL());

            // Only queue if payload is not null
            if (payload != null) {
                // Add the payload to the event queue for processing
                DataHolder.getInstance().getEventQueue().put(payload);
                log.debug("Treblle payload queued for request: " + requestId);
            } else {
                log.warn("Treblle payload is null. Skipping event for request: " + requestId);
            }
        } catch (Exception e) {
            // Never let Treblle SDK errors affect user requests
            log.error("Error in Treblle handleResponseOutFlow: " + e.getMessage(), e);
        }
        return true;
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
            // Calculate the response time in milliseconds
            responseTime = System.currentTimeMillis() - rtStartTime;
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
            // Extract first valid IPv4 address from X-FORWARDED-FOR header
            String[] ips = xForwardedForHeader.split(",");
            clientIP = null;
            for (String ip : ips) {
                ip = ip.trim();
                // Check if it's a valid IPv4 address (simple check: no colons except port)
                if (isIPv4(ip)) {
                    clientIP = ip;
                    break;
                }
            }
            // If no IPv4 found, use the first IP
            if (clientIP == null && ips.length > 0) {
                clientIP = ips[0].trim();
            }
        } else {
            // Fallback to the remote address property from the Axis2 message context
            clientIP = (String) axis2Context.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);
        }
        // Return "bogon" if the client IP is empty as per schema
        if (StringUtils.isEmpty(clientIP)) {
            return "bogon";
        }
        // Remove port if present (for IPv4 only)
        if (clientIP.contains(":") && clientIP.split(":").length == 2) {
            log.debug("Port will be ignored and only the IP address will be picked from " + clientIP);
            clientIP = clientIP.split(":")[0];
        }

        return clientIP;
    }

    private boolean isIPv4(String ip) {
        // Remove port if present
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            if (parts.length == 2) {
                ip = parts[0];
            } else {
                // Multiple colons indicate IPv6
                return false;
            }
        }
        // Simple IPv4 validation: 4 parts separated by dots
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private JsonNode mergeQueryIntoBody(JsonNode body, Map<String, String> queryParams) {
        // If no query parameters, return the original body
        if (queryParams == null || queryParams.isEmpty()) {
            return body;
        }

        ObjectMapper objectMapper = new ObjectMapper();

        // If body is null, create a new ObjectNode with query params
        if (body == null) {
            com.fasterxml.jackson.databind.node.ObjectNode newBody = objectMapper.createObjectNode();
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                newBody.put(entry.getKey(), entry.getValue());
            }
            return newBody;
        }

        // If body is an ObjectNode, add query params to it
        if (body.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode objectNode =
                (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                // Only add if the key doesn't already exist in the body
                if (!objectNode.has(entry.getKey())) {
                    objectNode.put(entry.getKey(), entry.getValue());
                }
            }
            return objectNode;
        }

        // If body is not an object (array, string, etc.), return original body
        return body;
    }

    private TrebllePayload createPayload(org.apache.synapse.MessageContext messageContext, String gatewayURL) {
        try {
            return buildPayload(messageContext, gatewayURL);
        } catch (Exception e) {
            log.error("Error creating Treblle payload: " + e.getMessage(), e);
            return null;
        }
    }

    private TrebllePayload buildPayload(org.apache.synapse.MessageContext messageContext, String gatewayURL) {

        // Retrieve the Axis2 message context from the Synapse message context
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        // Retrieve and handle request headers
        Map<String, String> reqHeaders = (Map<String, String>) messageContext.getProperty(TREBLLE_REQ_HEADERS);
        if (reqHeaders == null) {
            log.warn("Request headers are null. Setting default empty map.");
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
        if (reqIp == null || reqIp.isEmpty()) {
            log.warn("Request IP is null or empty. Setting default value as per schema.");
            reqIp = "bogon";
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

        // Extract query parameters from URL
        Map<String, String> queryParams = new HashMap<>();
        if (reqURL.contains("?")) {
            String queryString = reqURL.substring(reqURL.indexOf("?") + 1);
            String[] params = queryString.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    queryParams.put(keyValue[0], keyValue[1]);
                } else if (keyValue.length == 1) {
                    queryParams.put(keyValue[0], "");
                }
            }
        }
        request.setQuery(queryParams);

        // Merge query parameters into request body
        JsonNode mergedBody = mergeQueryIntoBody(reqBody, queryParams);
        request.setBody(mergedBody);

        // Set route_path (null if not available)
        request.setRoutePath(null);

        // Create and initialize the Response object
        final Data data = new Data();
        final Response response = new Response();

        // Initialize errors as empty list
        List<RuntimeError> runtimeErrors = new ArrayList<>();

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

            runtimeErrors.add(runtimeError);
        }

        // Always set errors list (empty or with errors)
        data.setErrors(runtimeErrors);

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

        // Set UUID for the API
        payload.setInternalId((String) messageContext.getProperty("API_UUID"));

        // Set Name for the API
        payload.setInternalName((String) messageContext.getProperty(TREBLLE_API_NAME));

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
        // Parse the JSON string into a JsonNode
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            jsonNode = objectMapper.readTree(jsonPayloadToString);
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
            // Handle the UnknownHostException and set a default IP as per schema
            log.error("Unknown host exception: " + e.getMessage());
            serverIP = "bogon";
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
     * Check if a request has already been processed to prevent duplicates.
     *
     * @param requestId The unique request ID
     * @return true if duplicate, false otherwise
     */
    private boolean isDuplicateRequest(String requestId) {
        if (requestId == null) {
            return false;
        }

        // Clean up expired entries
        cleanupExpiredRequests();

        return processedRequests.containsKey(requestId);
    }

    /**
     * Mark a request as processed with current timestamp.
     *
     * @param requestId The unique request ID
     */
    private void markRequestAsProcessed(String requestId) {
        if (requestId != null) {
            processedRequests.put(requestId, System.currentTimeMillis());
        }
    }

    /**
     * Remove expired entries from the processed requests map.
     * Entries older than REQUEST_TTL_MS are removed.
     */
    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        processedRequests.entrySet().removeIf(entry -> (now - entry.getValue()) > REQUEST_TTL_MS);
    }

}
