package com.treblle.wso2publisher.handlers;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treblle.wso2publisher.dto.Data;
import com.treblle.wso2publisher.dto.Language;
import com.treblle.wso2publisher.dto.Metadata;
import com.treblle.wso2publisher.dto.OperatingSystem;
import com.treblle.wso2publisher.dto.Request;
import com.treblle.wso2publisher.dto.Response;
import com.treblle.wso2publisher.dto.RuntimeError;
import com.treblle.wso2publisher.dto.Server;
import com.treblle.wso2publisher.dto.TrebllePayload;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class APILogHandler extends AbstractHandler {

    private static final String HEADER_X_FORWARDED_FOR = "X-FORWARDED-FOR";
    private static final String TREBLLE_REQ_HEADERS = "TREBLLE_REQ_HEADERS";
    private static final String TREBLLE_REQ_BODY = "TREBLLE_REQ_BODY";
    private static final String TREBLLE_REQ_PATH = "TREBLLE_REQ_PATH";
    private static final String TREBLLE_REQ_METHOD = "TREBLLE_REQ_METHOD";
    private static final String TREBLLE_API_NAME = "TREBLLE_API_NAME";
    private static final String TREBLLE_REQ_IP = "TREBLLE_REQ_IP";
    private static final String TREBLLE_ROUTE_PATH = "TREBLLE_ROUTE_PATH";
    private static final String TREBLLE_API_UUID = "TREBLLE_API_UUID";
    private static final String TREBLLE_TENANT_DOMAIN = "TREBLLE_TENANT_DOMAIN";
    private static final String TREBLLE_APP_NAME = "TREBLLE_APP_NAME";
    private static final String TREBLLE_APP_ID = "TREBLLE_APP_ID";
    private static final String TREBLLE_USER_ID = "TREBLLE_USER_ID";
    private static final String TREBLLE_API_PUBLISHER = "TREBLLE_API_PUBLISHER";
    private static final String TREBLLE_PER_API_MASK_KEYWORDS = "TREBLLE_PER_API_MASK_KEYWORDS";
    private static final String TREBLLE_DISABLE_RESPONSE_BODY = "TREBLLE_DISABLE_RESPONSE_BODY";
    private static final String TREBLLE_META_API_VERSION = "TREBLLE_META_API_VERSION";
    private static final String TREBLLE_SUBSCRIBER = "TREBLLE_SUBSCRIBER";
    private static final String TREBLLE_META_APP_NAME = "TREBLLE_META_APP_NAME";
    private static final String TREBLLE_META_PUBLISHER = "TREBLLE_META_PUBLISHER";
    private static final String TREBLLE_META_CUSTOMER_IP = "TREBLLE_META_CUSTOMER_IP";
    private static final String TREBLLE_META_TENANT = "TREBLLE_META_TENANT";
    private static final String TREBLLE_META_HOST = "TREBLLE_META_HOST";
    private static final String TREBLLE_REQ_URL = "TREBLLE_REQ_URL";
    private static final String REST_URL_POSTFIX = "REST_URL_POSTFIX";
    private static final String HTTP_METHOD = "HTTP_METHOD";
    private static final String API_ELECTED_RESOURCE = "API_ELECTED_RESOURCE";
    private static final String CARBON_LOCAL_IP = "carbon.local.ip";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MASK_KEYWORDS_CACHE_MAX_SIZE = 1000;
    private static final Map<String, List<String>> apiMaskKeywordsCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, List<String>>(MASK_KEYWORDS_CACHE_MAX_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, List<String>> eldest) {
                return size() > MASK_KEYWORDS_CACHE_MAX_SIZE;
            }
        }
    );
    private static final int DISABLE_BODY_CACHE_MAX_SIZE = 1000;
    private static final Map<String, Boolean> apiDisableResponseBodyCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, Boolean>(DISABLE_BODY_CACHE_MAX_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                return size() > DISABLE_BODY_CACHE_MAX_SIZE;
            }
        }
    );
    private static String serverIP;

    private static final Log log = LogFactory.getLog(APILogHandler.class);

    @Override
    public boolean handleRequest(MessageContext messageContext) {
        try {
            if (!isEnabledTenantDomain(messageContext)) {
                return true;
            }

            // Log all MessageContext properties in debug mode for troubleshooting
            if (log.isDebugEnabled()) {
                logAllMessageContextProperties(messageContext);
            }

            // Get the Axis2 message context from the Synapse message context
            org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                    .getAxis2MessageContext();

            // Skip OPTIONS requests (CORS preflight) - they're not actual API usage
            String httpMethod = (String) axis2MsgContext.getProperty(HTTP_METHOD);
            if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping OPTIONS request - CORS preflight not tracked");
                }
                return true;
            }

            // Retrieve and set request headers
            Map<String, String> headersMap = getHeaders(messageContext);
            messageContext.setProperty(TREBLLE_REQ_HEADERS, headersMap);

            // Retrieve and set the request body
            JsonNode jsonNode = getMessageBody(messageContext);
            messageContext.setProperty(TREBLLE_REQ_BODY, jsonNode);

            // Retrieve and set the request path
            String reqPath = (String) axis2MsgContext.getProperty(REST_URL_POSTFIX);
            messageContext.setProperty(TREBLLE_REQ_PATH, reqPath);

            // Retrieve and set the full request URL
            String fullUrl = buildFullRequestUrl(axis2MsgContext, headersMap, messageContext);
            messageContext.setProperty(TREBLLE_REQ_URL, fullUrl);

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

            // Capture API name (available immediately since auth handler already ran)
            String apiName = getApiName(messageContext);
            messageContext.setProperty(TREBLLE_API_NAME, apiName);

            if (log.isDebugEnabled()) {
                log.debug("Captured API name: " + (apiName != null ? apiName : "NULL"));
            }

            // Capture API UUID (available immediately since auth handler already ran)
            String apiUuid = getApiUuid(messageContext);
            messageContext.setProperty(TREBLLE_API_UUID, apiUuid);

            if (log.isDebugEnabled()) {
                log.debug("Captured API UUID: " + (apiUuid != null ? apiUuid : "NULL"));
            }

            // Capture enriched properties from the handler chain (available after APIAuthenticationHandler)
            String tenantDomain = (String) messageContext.getProperty("tenant.info.domain");
            messageContext.setProperty(TREBLLE_TENANT_DOMAIN, tenantDomain);

            String appName = (String) messageContext.getProperty("APPLICATION_NAME");
            messageContext.setProperty(TREBLLE_APP_NAME, appName);

            String appId = (String) messageContext.getProperty("APPLICATION_ID");
            messageContext.setProperty(TREBLLE_APP_ID, appId);

            String userId = (String) messageContext.getProperty("END_USER_NAME");
            messageContext.setProperty(TREBLLE_USER_ID, userId);

            String apiPublisher = (String) messageContext.getProperty("API_PUBLISHER");
            messageContext.setProperty(TREBLLE_API_PUBLISHER, apiPublisher);

            // Capture metadata fields
            messageContext.setProperty(TREBLLE_META_API_VERSION, messageContext.getProperty("api.ut.api_version"));
            messageContext.setProperty(TREBLLE_SUBSCRIBER, messageContext.getProperty(getClaim(headersMap.get("X-JWT-Assertion"),"subscriber")));
            messageContext.setProperty(TREBLLE_META_APP_NAME, messageContext.getProperty("api.ut.application.name"));
            messageContext.setProperty(TREBLLE_META_PUBLISHER, messageContext.getProperty("api.ut.apiPublisher"));
            messageContext.setProperty(TREBLLE_META_CUSTOMER_IP, messageContext.getProperty("api.analytics.user.ip"));
            messageContext.setProperty(TREBLLE_META_TENANT, messageContext.getProperty(TREBLLE_TENANT_DOMAIN));
            messageContext.setProperty(TREBLLE_META_HOST, messageContext.getProperty("api.ut.hostName"));

            // Capture per-API mask keywords from WSO2 custom properties
            List<String> perApiMaskKeywords = getPerApiMaskKeywords(messageContext, apiUuid);
            if (perApiMaskKeywords != null && !perApiMaskKeywords.isEmpty()) {
                messageContext.setProperty(TREBLLE_PER_API_MASK_KEYWORDS, perApiMaskKeywords);
                if (log.isDebugEnabled()) {
                    log.debug("Treblle: Per-API mask keywords for API " + apiUuid + ": " + perApiMaskKeywords);
                }
            }

            // Capture per-API disable response body flag from WSO2 custom properties
            boolean disableResponseBody = getDisableResponseBody(messageContext, apiUuid);
            messageContext.setProperty(TREBLLE_DISABLE_RESPONSE_BODY, disableResponseBody);
            if (log.isDebugEnabled()) {
                log.debug("Treblle: Disable response body for API " + (apiUuid != null ? apiUuid : "unknown") + ": " + disableResponseBody);
            }

            return true;
        } catch (Exception e) {
            log.error("Treblle handler failed during request handling. Continuing request processing.", e);
            return true; // Always return true to not block the request
        }
    }

    @Override
    public boolean handleResponse(MessageContext messageContext) {
        if (log.isDebugEnabled()) {
            log.debug("Treblle: handleResponse called");
        }
        try {
            if (!isEnabledTenantDomain(messageContext)) {
                if (log.isDebugEnabled()) {
                    log.debug("Treblle: Tenant domain not enabled, skipping");
                }
                return true;
            }

            // Skip if request method is not set (e.g., OPTIONS was filtered in handleRequest)
            String method = (String) messageContext.getProperty(TREBLLE_REQ_METHOD);
            if (method == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Treblle: Request method is null, skipping (likely OPTIONS filtered)");
                }
                return true;
            }

            if (log.isDebugEnabled()) {
                log.debug("Treblle: Creating payload for method: " + method);
            }
            // Create a TrebllePayload object using the message context and gateway URL
            TrebllePayload payload = createPayload(messageContext, DataHolder.getInstance().getGatewayURL());
            if (log.isDebugEnabled()) {
                log.debug("Treblle: Payload created, enqueueing...");
            }
            // Add the payload to the event queue for processing
            DataHolder.getInstance().getEventQueue().put(payload);
            if (log.isDebugEnabled()) {
                log.debug("Treblle: Payload enqueued successfully");
            }
            return true;
        } catch (Exception e) {
            log.error("Treblle handler failed during response handling. Continuing request processing.", e);
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
        String[] parts = clientIP.split(":");
        if (parts.length == 2) {
            log.debug("Port will be ignored and only the IP address will be picked from " + clientIP);
            clientIP = parts[0];
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

        String wso2Version = System.getProperty("carbon.product.version");
        server.setSoftware(wso2Version != null ? "WSO2 " + wso2Version : "WSO2 API Manager");
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

        String reqUrl = (String) messageContext.getProperty(TREBLLE_REQ_URL);
        request.setUrl(reqUrl);
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

            Object errorMessageObj = messageContext.getProperty("ERROR_MESSAGE");
            String errorType = errorMessageObj instanceof String && !((String) errorMessageObj).isEmpty()
                    ? (String) errorMessageObj
                    : "HTTP " + responseCode;

            Object errorDetailObj = messageContext.getProperty("ERROR_DETAIL");
            String errorDetail = errorDetailObj instanceof String && !((String) errorDetailObj).isEmpty()
                    ? (String) errorDetailObj
                    : getHttpReasonPhrase(responseCode);

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
            response.setSize((long) jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
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

        // Set enriched properties from the handler chain
        payload.setTenantId((String) messageContext.getProperty(TREBLLE_TENANT_DOMAIN));
        payload.setAppName((String) messageContext.getProperty(TREBLLE_APP_NAME));
        payload.setAppId((String) messageContext.getProperty(TREBLLE_APP_ID));
        payload.setUserId((String) messageContext.getProperty(TREBLLE_USER_ID));
        payload.setApiPublisher((String) messageContext.getProperty(TREBLLE_API_PUBLISHER));

        // Set per-API mask keywords for downstream masking
        @SuppressWarnings("unchecked")
        List<String> perApiMaskKeywords = (List<String>) messageContext.getProperty(TREBLLE_PER_API_MASK_KEYWORDS);
        if (perApiMaskKeywords != null && !perApiMaskKeywords.isEmpty()) {
            payload.setPerApiMaskKeywords(perApiMaskKeywords);
        }

        // Set disable response body flag for downstream processing
        Object disableResponseBodyProp = messageContext.getProperty(TREBLLE_DISABLE_RESPONSE_BODY);
        if (Boolean.TRUE.equals(disableResponseBodyProp)) {
            payload.setDisableResponseBody(true);
        }

        // Build and set metadata
        Metadata metadata = new Metadata();
        metadata.setApiVersion(nullIfEmpty(messageContext.getProperty(TREBLLE_META_API_VERSION)));
        metadata.setPublisher(nullIfEmpty(messageContext.getProperty(TREBLLE_META_PUBLISHER)));
        metadata.setCustomerIp(nullIfEmpty(messageContext.getProperty(TREBLLE_META_CUSTOMER_IP)));
        metadata.setTenant(nullIfEmpty(messageContext.getProperty(TREBLLE_META_TENANT)));
        metadata.setHost(nullIfEmpty(messageContext.getProperty(TREBLLE_META_HOST)));

        String subscriberName = nullIfEmpty(messageContext.getProperty(TREBLLE_SUBSCRIBER));
        String metaAppName = nullIfEmpty(messageContext.getProperty(TREBLLE_META_APP_NAME));
        if (subscriberName != null && metaAppName != null) {
            metadata.setUserId(metaAppName + "-" + subscriberName);
        } else if (subscriberName != null) {
            metadata.setUserId(subscriberName);
        } else if (metaAppName != null) {
            metadata.setUserId(metaAppName);
        }

        payload.getData().setMetadata(metadata);

        return payload;
    }

    private String buildFullRequestUrl(org.apache.axis2.context.MessageContext axis2MsgContext,
                                        Map<String, String> headersMap,
                                        MessageContext messageContext) {
        String path = (String) messageContext.getProperty("REST_FULL_REQUEST_PATH");
        if (path == null) {
            path = "";
        }

        String host = null;
        for (Map.Entry<String, String> entry : headersMap.entrySet()) {
            if ("host".equalsIgnoreCase(entry.getKey())) {
                host = entry.getValue();
                break;
            }
        }

        String scheme = axis2MsgContext.getIncomingTransportName();
        if (scheme == null) {
            scheme = "http";
        }

        if (host != null) {
            return scheme + "://" + host + path;
        }
        return path;
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

        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        try {
            RelayUtils.buildMessage(axis2MsgContext);
        } catch (Exception e) {
            log.error("Error building message: " + e.getMessage());
            return null;
        }

        // Determine content type case-insensitively
        String contentType = null;
        Map<String, String> headers = getHeaders(messageContext);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                contentType = entry.getValue().toLowerCase();
                break;
            }
        }

        // Try JSON first — covers application/json requests and response body validation
        try {
            String jsonStr = JsonUtil.jsonPayloadToString(axis2MsgContext);
            if (jsonStr != null && !jsonStr.isEmpty()) {
                return OBJECT_MAPPER.readTree(jsonStr);
            }
        } catch (Exception e) {
            log.debug("Body is not JSON: " + e.getMessage());
        }

        // For URL-encoded and multipart, extract fields from the SOAP body element tree
        if (contentType != null && (contentType.contains("application/x-www-form-urlencoded")
                || contentType.contains("multipart/form-data"))) {
            try {
                org.apache.axiom.om.OMElement bodyElement =
                        axis2MsgContext.getEnvelope().getBody().getFirstElement();
                if (bodyElement != null) {
                    // Try child elements first (Synapse message builder converts form fields to XML)
                    Map<String, String> formData = new HashMap<>();
                    java.util.Iterator<?> children = bodyElement.getChildElements();
                    while (children.hasNext()) {
                        org.apache.axiom.om.OMElement child = (org.apache.axiom.om.OMElement) children.next();
                        formData.put(child.getLocalName(), child.getText());
                    }
                    if (!formData.isEmpty()) {
                        return OBJECT_MAPPER.valueToTree(formData);
                    }

                    // Fallback: raw URL-encoded text in the body element
                    String rawText = bodyElement.getText();
                    if (rawText != null && !rawText.isEmpty()) {
                        return parseUrlEncodedString(rawText);
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing form body: " + e.getMessage());
            }
        }

        return null;
    }

    private JsonNode parseUrlEncodedString(String raw) {
        Map<String, String> params = new HashMap<>();
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    params.put(key, value);
                } catch (Exception e) {
                    log.debug("Skipping malformed URL-encoded pair: " + pair);
                }
            }
        }
        return params.isEmpty() ? null : OBJECT_MAPPER.valueToTree(params);
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
    private String getHttpReasonPhrase(int statusCode) {
        switch (statusCode) {
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 406: return "Not Acceptable";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 411: return "Length Required";
            case 412: return "Precondition Failed";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Entity";
            case 423: return "Locked";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            case 505: return "HTTP Version Not Supported";
            default:  return "HTTP Error";
        }
    }

    private String nullIfEmpty(Object value) {
        if (value == null) return null;
        String str = value.toString();
        return str.isEmpty() ? null : str;
    }

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

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                String routePath = (String) messageContext.getProperty(propertyName);
                if (routePath != null && !routePath.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Treblle: Found route path using property '" + propertyName + "': " + routePath);
                    }
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
        if (log.isDebugEnabled()) {
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
        try {
            Object propertyValue = messageContext.getProperty("api.ut.api");
            if (propertyValue != null) {
                String apiName = propertyValue.toString();
                if (!apiName.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Treblle: Found API name using property 'api.ut.api': " + apiName);
                    }
                    return apiName;
                }
            }
        } catch (Exception e) {
            log.warn("Treblle: Error reading property 'api.ut.api': " + e.getMessage());
        }
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

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                Object propertyValue = messageContext.getProperty(propertyName);
                if (propertyValue != null) {
                    String apiUuid = propertyValue.toString();
                    if (!apiUuid.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Treblle: Found API UUID using property '" + propertyName + "': " + apiUuid);
                        }
                        return apiUuid;
                    }
                }
            } catch (Exception e) {
                log.warn("Treblle: Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }

        // If all direct property lookups fail, log available properties for debugging
        if (log.isDebugEnabled()) {
            logAvailablePropertiesForUuid(messageContext);
        }

        log.warn("Treblle: Unable to determine API UUID. The 'internal_id' field will be null.");
        return null;
    }

    /**
     * Get per-API mask keywords from WSO2 custom properties.
     * Uses a cache keyed by API UUID to avoid re-parsing on every request.
     * Tries multiple MessageContext property names to find the custom property.
     *
     * @param messageContext the Synapse message context
     * @param apiUuid the API UUID for cache keying (may be null)
     * @return list of per-API mask keywords, or null if not configured
     */
    @SuppressWarnings("unchecked")
    private List<String> getPerApiMaskKeywords(MessageContext messageContext, String apiUuid) {
        // Check cache first if we have an API UUID
        if (apiUuid != null) {
            List<String> cached = apiMaskKeywordsCache.get(apiUuid);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Treblle: Per-API mask keywords cache hit for API " + apiUuid);
                }
                return cached;
            }
        }

        String maskKeywordsValue = null;

        // Try direct custom property first
        Object directProp = messageContext.getProperty("treblle_mask_keywords");
        if (directProp instanceof String && !((String) directProp).isEmpty()) {
            maskKeywordsValue = (String) directProp;
            if (log.isDebugEnabled()) {
                log.debug("Treblle: Found per-API mask keywords from 'treblle_mask_keywords': " + maskKeywordsValue);
            }
        }

        // Try additionalProperties map
        if (maskKeywordsValue == null) {
            Object additionalProps = messageContext.getProperty("additionalProperties");
            if (additionalProps instanceof Map) {
                Object value = ((Map<String, Object>) additionalProps).get("treblle_mask_keywords");
                if (value instanceof String && !((String) value).isEmpty()) {
                    maskKeywordsValue = (String) value;
                    if (log.isDebugEnabled()) {
                        log.debug("Treblle: Found per-API mask keywords from 'additionalProperties': " + maskKeywordsValue);
                    }
                }
            }
        }

        // Try api.ut.additionalProperties map
        if (maskKeywordsValue == null) {
            Object utAdditionalProps = messageContext.getProperty("api.ut.additionalProperties");
            if (utAdditionalProps instanceof Map) {
                Object value = ((Map<String, Object>) utAdditionalProps).get("treblle_mask_keywords");
                if (value instanceof String && !((String) value).isEmpty()) {
                    maskKeywordsValue = (String) value;
                    if (log.isDebugEnabled()) {
                        log.debug("Treblle: Found per-API mask keywords from 'api.ut.additionalProperties': " + maskKeywordsValue);
                    }
                }
            }
        }

        if (maskKeywordsValue == null) {
            if (log.isDebugEnabled()) {
                log.debug("Treblle: No per-API mask keywords found for API " + (apiUuid != null ? apiUuid : "unknown"));
            }
            return null;
        }

        // Parse comma-separated keywords, trimming whitespace
        List<String> keywords = Arrays.stream(maskKeywordsValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Cache by API UUID if available
        if (apiUuid != null && !keywords.isEmpty()) {
            apiMaskKeywordsCache.put(apiUuid, keywords);
        }

        return keywords.isEmpty() ? null : keywords;
    }

    /**
     * Get the per-API disable response body flag from WSO2 custom properties.
     * Uses a cache keyed by API UUID to avoid re-parsing on every request.
     * Tries multiple MessageContext property names to find the custom property.
     *
     * @param messageContext the Synapse message context
     * @param apiUuid the API UUID for cache keying (may be null)
     * @return true if response body capture should be disabled, false otherwise
     */
    @SuppressWarnings("unchecked")
    private boolean getDisableResponseBody(MessageContext messageContext, String apiUuid) {
        // Check cache first if we have an API UUID
        if (apiUuid != null) {
            Boolean cached = apiDisableResponseBodyCache.get(apiUuid);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Treblle: Disable response body cache hit for API " + apiUuid + ": " + cached);
                }
                return cached;
            }
        }

        String flagValue = null;

        // Try direct custom property first
        Object directProp = messageContext.getProperty("treblle_disable_response_body");
        if (directProp instanceof String && !((String) directProp).isEmpty()) {
            flagValue = (String) directProp;
            if (log.isDebugEnabled()) {
                log.debug("Treblle: Found disable response body from 'treblle_disable_response_body': " + flagValue);
            }
        }

        // Try additionalProperties map
        if (flagValue == null) {
            Object additionalProps = messageContext.getProperty("additionalProperties");
            if (additionalProps instanceof Map) {
                Object value = ((Map<String, Object>) additionalProps).get("treblle_disable_response_body");
                if (value instanceof String && !((String) value).isEmpty()) {
                    flagValue = (String) value;
                    if (log.isDebugEnabled()) {
                        log.debug("Treblle: Found disable response body from 'additionalProperties': " + flagValue);
                    }
                }
            }
        }

        // Try api.ut.additionalProperties map
        if (flagValue == null) {
            Object utAdditionalProps = messageContext.getProperty("api.ut.additionalProperties");
            if (utAdditionalProps instanceof Map) {
                Object value = ((Map<String, Object>) utAdditionalProps).get("treblle_disable_response_body");
                if (value instanceof String && !((String) value).isEmpty()) {
                    flagValue = (String) value;
                    if (log.isDebugEnabled()) {
                        log.debug("Treblle: Found disable response body from 'api.ut.additionalProperties': " + flagValue);
                    }
                }
            }
        }

        boolean result = "true".equalsIgnoreCase(flagValue);

        // Cache by API UUID if available
        if (apiUuid != null) {
            apiDisableResponseBodyCache.put(apiUuid, result);
        }

        return result;
    }


    /**
     * Log all available properties in MessageContext that might contain API UUID information.
     * This is useful for debugging and discovering which properties are available in different
     * WSO2 API Manager versions.
     *
     * @param messageContext the Synapse message context
     */
    private void logAvailablePropertiesForUuid(MessageContext messageContext) {
        log.debug("Treblle: Listing all MessageContext properties containing 'UUID', 'API', or 'IDENTIFIER':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("UUID") || upperKey.contains("API") ||
                    upperKey.contains("IDENTIFIER")) {

                    Object value = messageContext.getProperty(key);
                    log.debug("Treblle:   - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.debug("Treblle:   (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.debug("Treblle: Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("UUID") || upperKey.contains("API") ||
                    upperKey.contains("IDENTIFIER")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.debug("Treblle:   - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.debug("Treblle:   (No relevant properties found in Axis2 MessageContext)");
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
        log.debug("Treblle: Listing all MessageContext properties containing 'REST', 'API', 'RESOURCE', or 'PATTERN':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("REST") || upperKey.contains("API") ||
                    upperKey.contains("RESOURCE") || upperKey.contains("PATTERN") ||
                    upperKey.contains("URI") || upperKey.contains("TEMPLATE")) {

                    Object value = messageContext.getProperty(key);
                    log.debug("Treblle:   - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.debug("Treblle:   (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.debug("Treblle: Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("REST") || upperKey.contains("API") ||
                    upperKey.contains("RESOURCE") || upperKey.contains("PATTERN") ||
                    upperKey.contains("URI") || upperKey.contains("TEMPLATE")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.debug("Treblle:   - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.debug("Treblle:   (No relevant properties found in Axis2 MessageContext)");
            }

        } catch (Exception e) {
            log.error("Treblle: Error logging available properties: " + e.getMessage(), e);
        }
    }

    /**
     * Log ALL available properties in MessageContext for comprehensive debugging.
     * This method logs every single property from both Synapse and Axis2 MessageContext
     * to help developers understand what data is available in their WSO2 environment.
     * Only runs when log.isDebugEnabled() is true.
     *
     * @param messageContext the Synapse message context
     */
    private void logAllMessageContextProperties(MessageContext messageContext) {
        log.debug("==================== TREBLLE DEBUG: ALL MESSAGE CONTEXT PROPERTIES ====================");

        try {
            // Log Synapse MessageContext properties
            log.debug("--- Synapse MessageContext Properties ---");
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();

            if (propertyKeys == null || propertyKeys.isEmpty()) {
                log.debug("  (No properties found in Synapse MessageContext)");
            } else {
                log.debug("  Total Synapse properties: " + propertyKeys.size());
                java.util.List<String> sortedKeys = new java.util.ArrayList<>(propertyKeys);
                java.util.Collections.sort(sortedKeys);

                for (String key : sortedKeys) {
                    try {
                        Object value = messageContext.getProperty(key);
                        String valueStr;

                        if (value == null) {
                            valueStr = "null";
                        } else if (value instanceof Map) {
                            valueStr = "[Map with " + ((Map<?, ?>) value).size() + " entries] " + value.getClass().getName();
                        } else if (value instanceof java.util.Collection) {
                            valueStr = "[Collection with " + ((java.util.Collection<?>) value).size() + " items] " + value.getClass().getName();
                        } else {
                            valueStr = String.valueOf(value);
                            // Truncate very long values
                            if (valueStr.length() > 200) {
                                valueStr = valueStr.substring(0, 200) + "... [truncated, total length: " + valueStr.length() + "]";
                            }
                        }

                        log.debug("  [Synapse] " + key + " = " + valueStr + " (type: " + value.getClass().getName() + ")");
                    } catch (Exception e) {
                        log.debug("  [Synapse] " + key + " = <error reading value: " + e.getMessage() + ">");
                    }
                }
            }

            // Log Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.debug("--- Axis2 MessageContext Properties ---");
            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();

            if (!propertyNames.hasNext()) {
                log.debug("  (No properties found in Axis2 MessageContext)");
            } else {
                java.util.List<String> axis2Keys = new java.util.ArrayList<>();
                while (propertyNames.hasNext()) {
                    axis2Keys.add(String.valueOf(propertyNames.next()));
                }
                java.util.Collections.sort(axis2Keys);

                log.debug("  Total Axis2 properties: " + axis2Keys.size());

                for (String key : axis2Keys) {
                    try {
                        Object value = axis2MsgContext.getProperty(key);
                        String valueStr;

                        if (value == null) {
                            valueStr = "null";
                        } else if (value instanceof Map) {
                            valueStr = "[Map with " + ((Map<?, ?>) value).size() + " entries] " + value.getClass().getName();
                        } else if (value instanceof java.util.Collection) {
                            valueStr = "[Collection with " + ((java.util.Collection<?>) value).size() + " items] " + value.getClass().getName();
                        } else {
                            valueStr = String.valueOf(value);
                            // Truncate very long values
                            if (valueStr.length() > 200) {
                                valueStr = valueStr.substring(0, 200) + "... [truncated, total length: " + valueStr.length() + "]";
                            }
                        }

                        log.debug("  [Axis2] " + key + " = " + valueStr + " (type: " + value.getClass().getName() + ")");
                    } catch (Exception e) {
                        log.debug("  [Axis2] " + key + " = <error reading value: " + e.getMessage() + ">");
                    }
                }
            }

            log.debug("==================== END TREBLLE DEBUG ====================");

        } catch (Exception e) {
            log.error("Treblle: Error logging all MessageContext properties: " + e.getMessage(), e);
        }
    }

    private String getClaim(String jwt, String claim) {
        if (jwt == null) return null;
        String payload = jwt.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payload));

        String search = "\"" + claim + "\":";
        int start = json.indexOf(search);
        if (start == -1) return null;

        start += search.length();
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);

        return json.substring(start, end)
                .replace("\"", "")
                .trim();
    }

}
