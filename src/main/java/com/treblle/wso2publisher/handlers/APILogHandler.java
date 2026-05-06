package com.treblle.wso2publisher.handlers;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.treblle.wso2publisher.commons.PropertyUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;

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

    private Properties additionalProperties = new Properties();

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
    private static final int MASK_KEYWORDS_CACHE_MAX_SIZE = 1000;
    private static final Cache<String, List<String>> apiMaskKeywordsCache = CacheBuilder.newBuilder()
        .maximumSize(MASK_KEYWORDS_CACHE_MAX_SIZE)
        .expireAfterWrite(60, TimeUnit.MINUTES)
        .build();
    private static final int DISABLE_BODY_CACHE_MAX_SIZE = 1000;
    private static final Cache<String, Boolean> apiDisableResponseBodyCache = CacheBuilder.newBuilder()
        .maximumSize(DISABLE_BODY_CACHE_MAX_SIZE)
        .expireAfterWrite(60, TimeUnit.MINUTES)
        .build();
    private static String serverIP;

    private static final Log log = LogFactory.getLog(APILogHandler.class);

    protected Properties getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(String additionalPropertiesJsonXmlEscaped) {
        if (log.isDebugEnabled()) {
            log.debug("[TREBLLE]:setAdditionalProperties(" + additionalPropertiesJsonXmlEscaped + ")");
        }
        this.additionalProperties.clear();
        if (additionalPropertiesJsonXmlEscaped != null && !additionalPropertiesJsonXmlEscaped.trim().isEmpty()) {
            String additionalPropertiesJson = StringEscapeUtils.unescapeXml(additionalPropertiesJsonXmlEscaped);
            try {
                JSONObject jsonObject = new JSONObject(additionalPropertiesJson);
                this.additionalProperties.putAll(PropertyUtils.toProperties(jsonObject));
            } catch (JSONException e) {
                log.warn("[TREBLLE]:Unable to parse additionalProperties JSON - " + e.getMessage());
            }
        }
    }

    @Override
    public boolean handleRequest(MessageContext messageContext) {
        final long reqStart = log.isDebugEnabled() ? System.nanoTime() : 0;
        try {
            if (!isEnabledTenantDomain(messageContext)) {
                return true;
            }

            // Get the Axis2 message context from the Synapse message context
            org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                    .getAxis2MessageContext();

            // Skip OPTIONS requests (CORS preflight) - they're not actual API usage
            String httpMethod = (String) axis2MsgContext.getProperty(HTTP_METHOD);
            if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]: Skipping OPTIONS request - CORS preflight not tracked");
                }
                return true;
            }

            // Retrieve and set request headers
            Map<String, String> headersMap = getHeaders(messageContext);
            messageContext.setProperty(TREBLLE_REQ_HEADERS, headersMap);

            // Skip body capture for methods that carry no body — avoids RelayUtils.buildMessage() cost
            String reqBodyRaw = null;
            if (!"GET".equalsIgnoreCase(httpMethod) && !"HEAD".equalsIgnoreCase(httpMethod)
                    && !"DELETE".equalsIgnoreCase(httpMethod)) {
                reqBodyRaw = getMessageBodyRaw(messageContext, headersMap);
            }
            messageContext.setProperty(TREBLLE_REQ_BODY, reqBodyRaw);

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
                log.debug("[TREBLLE]: Captured route path: " + (routePath != null ? routePath : "NULL"));
            }

            // Capture API name (available immediately since auth handler already ran)
            String apiName = getApiName(messageContext);
            messageContext.setProperty(TREBLLE_API_NAME, apiName);

            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]: Captured API name: " + (apiName != null ? apiName : "NULL"));
            }

            // Capture API UUID (available immediately since auth handler already ran)
            String apiUuid = getApiUuid(messageContext);
            messageContext.setProperty(TREBLLE_API_UUID, apiUuid);

            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]: Captured API UUID: " + (apiUuid != null ? apiUuid : "NULL"));
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
                    log.debug("[TREBLLE]:Per-API mask keywords for API " + apiUuid + ": " + perApiMaskKeywords);
                }
            }

            // Capture per-API disable response body flag from WSO2 custom properties
            boolean disableResponseBody = getDisableResponseBody(messageContext, apiUuid);
            messageContext.setProperty(TREBLLE_DISABLE_RESPONSE_BODY, disableResponseBody);
            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]:Disable response body for API " + (apiUuid != null ? apiUuid : "unknown") + ": " + disableResponseBody);
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("[TREBLLE]: Perfromance handleRequest: %.2f ms", (System.nanoTime() - reqStart) / 1_000_000.0));
            }
            return true;
        } catch (Exception e) {
            log.error("[TREBLLE]: Handler failed during request handling. Continuing request processing.", e);
            return true; // Always return true to not block the request
        }
    }

    @Override
    public boolean handleResponse(MessageContext messageContext) {
        if (log.isDebugEnabled()) {
            log.debug("[TREBLLE]:handleResponse called");
        }
        try {
            if (!isEnabledTenantDomain(messageContext)) {
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]:Tenant domain not enabled, skipping");
                }
                return true;
            }

            // Skip if request method is not set (e.g., OPTIONS was filtered in handleRequest)
            String method = (String) messageContext.getProperty(TREBLLE_REQ_METHOD);
            if (method == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]:Request method is null, skipping (likely OPTIONS filtered)");
                }
                return true;
            }

            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]:Creating payload for method: " + method);
            }
            // Create a TrebllePayload object using the message context and gateway URL
            final long payloadStart = log.isDebugEnabled() ? System.nanoTime() : 0;
            TrebllePayload payload = createPayload(messageContext, DataHolder.getInstance().getGatewayURL());
            if (log.isDebugEnabled()) {
                log.debug(String.format("[TREBLLE]: Perfromance handleResponse.createPayload: %.2f ms", (System.nanoTime() - payloadStart) / 1_000_000.0));
                log.debug("[TREBLLE]:Payload created, enqueueing...");
            }
            // Add the payload to the event queue for processing
            final long enqueueStart = log.isDebugEnabled() ? System.nanoTime() : 0;
            DataHolder.getInstance().getEventQueue().put(payload);
            if (log.isDebugEnabled()) {
                log.debug(String.format("[TREBLLE]: Perfromance handleResponse.enqueue: %.2f ms", (System.nanoTime() - enqueueStart) / 1_000_000.0));
                log.debug("[TREBLLE]: Payload enqueued successfully");
            }
            return true;
        } catch (Exception e) {
            log.error("[TREBLLE]: Handler failed during response handling. Continuing request processing.", e);
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
            log.error("[TREBLLE]: Error getResponseTime - " + e.getMessage(), e);
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
            log.debug("[TREBLLE]: Port will be ignored and only the IP address will be picked from " + clientIP);
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
            log.error("[TREBLLE]: Request headers are null. Setting a default value.");
            reqHeaders = new HashMap<String, String>();
        }
        // Retrieve the raw request body string captured in handleRequest
        String reqBodyRaw = (String) messageContext.getProperty(TREBLLE_REQ_BODY);

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
            log.warn("[TREBLLE]: Request IP is null. Setting a default value.");
            reqIp = "127.0.0.1";
        }
        request.setIp(reqIp);

        String userAgent = (String) reqHeaders.get("User-Agent");
        if (userAgent == null) {
            log.warn("[TREBLLE]: User-Agent header is null. Setting a default value.");
            userAgent = "";
        }
        request.setUserAgent(userAgent);

        String method = (String) messageContext.getProperty(TREBLLE_REQ_METHOD);
        if (method == null) {
            log.warn("[TREBLLE]: Request method is null. Setting a default value.");
            method = "GET";
        }
        request.setMethod(method);

        String reqUrl = (String) messageContext.getProperty(TREBLLE_REQ_URL);
        request.setUrl(reqUrl);
        request.setHeaders(reqHeaders);
        request.setBodyRaw(reqBodyRaw);

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
        Map<String, String> responseHeaderMap = getHeaders(messageContext);
        String responseBodyRaw = getMessageBodyRaw(messageContext, responseHeaderMap);
        response.setBodyRaw(responseBodyRaw);
        response.setSize(responseBodyRaw != null
                ? (long) responseBodyRaw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0L);
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
            log.debug("[TREBLLE]: Transport headers are null.");
            return headersMap;
        }
        // Populate the headers map with the transport headers
        for (Object key : headers.keySet()) {
            headersMap.put(key.toString(), headers.get(key).toString());
        }
        return headersMap;
    }

    private String getMessageBodyRaw(MessageContext messageContext, Map<String, String> headers) {

        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        try {
            RelayUtils.buildMessage(axis2MsgContext);
        } catch (Exception e) {
            log.error("[TREBLLE]: Error building message: " + e.getMessage());
            return null;
        }

        // Determine content type case-insensitively
        String contentType = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                contentType = entry.getValue().toLowerCase();
                break;
            }
        }

        // Try JSON first — covers application/json and cases where Content-Type is missing/wrong
        try {
            String jsonStr = JsonUtil.jsonPayloadToString(axis2MsgContext);
            if (jsonStr != null && !jsonStr.isEmpty()) {
                return jsonStr;
            }
        } catch (Exception e) {
            log.debug("[TREBLLE]: Body is not JSON: " + e.getMessage());
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
                        return new JSONObject(formData).toString();
                    }

                    // Fallback: raw URL-encoded text in the body element
                    String rawText = bodyElement.getText();
                    if (rawText != null && !rawText.isEmpty()) {
                        return parseUrlEncodedBodyRaw(rawText);
                    }
                }
            } catch (Exception e) {
                log.debug("[TREBLLE]: Error parsing form body: " + e.getMessage());
            }
        }

        return null;
    }

    private String parseUrlEncodedBodyRaw(String raw) {
        Map<String, String> params = new HashMap<>();
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    params.put(key, value);
                } catch (Exception e) {
                    log.debug("[TREBLLE]: Skipping malformed URL-encoded pair: " + pair);
                }
            }
        }
        return params.isEmpty() ? null : new JSONObject(params).toString();
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
            log.error("[TREBLLE]: Unknown host exception: " + e.getMessage());
            serverIP = "127.0.0.1";
        }

        return serverIP;
    }

     private boolean isEnabledTenantDomain(MessageContext messageContext) {

        // Retrieve the tenant domain from the message context
        String tenantDomain = (String) messageContext.getProperty("tenant.info.domain");

        if (tenantDomain == null) {
            log.warn("[TREBLLE]: Tenant domain is null. Skipping the handler.");
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
        // API_ELECTED_RESOURCE is the canonical property for the route template (e.g. /users/{id}).
        // REST_URL_PATTERN is a fallback for older WSO2 versions that use a different name.
        // Other candidates (api.ut.resource, REST_SUB_REQUEST_PATH) return the actual resolved
        // URL path, not the template, and would corrupt route_path on parameterized routes.
        String[] propertyNames = {
            "API_ELECTED_RESOURCE",
            "REST_URL_PATTERN"
        };

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                String routePath = (String) messageContext.getProperty(propertyName);
                if (routePath != null && !routePath.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[TREBLLE]:Found route path using property '" + propertyName + "': " + routePath);
                    }
                    return routePath;
                } else {
                    log.debug("[TREBLLE]:Property '" + propertyName + "' is " +
                        (routePath == null ? "null" : "empty"));
                }
            } catch (Exception e) {
                log.warn("[TREBLLE]:Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }

        // If all direct property lookups fail, log available properties for debugging
        if (log.isDebugEnabled()) {
            logAvailableProperties(messageContext);
        }

        log.warn("[TREBLLE]:Unable to determine route path template. The 'route_path' field will be null.");
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
        // API_NAME is the direct WSO2 property; api.ut.api is the analytics-layer equivalent.
        String[] propertyNames = { "API_NAME", "api.ut.api" };
        for (String propertyName : propertyNames) {
            try {
                Object propertyValue = messageContext.getProperty(propertyName);
                if (propertyValue != null) {
                    String apiName = propertyValue.toString();
                    if (!apiName.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("[TREBLLE]:Found API name using property '" + propertyName + "': " + apiName);
                        }
                        return apiName;
                    }
                }
            } catch (Exception e) {
                log.warn("[TREBLLE]:Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }
        log.warn("[TREBLLE]:Unable to determine API name. The 'internal_name' field will be null.");
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
        // API_UUID is the confirmed working property on WSO2 4.x. api.uuid and __api.uuid
        // are kept as fallbacks for older versions. Candidates like API_IDENTIFIER return
        // an object whose toString() is not a bare UUID string.
        String[] propertyNames = {
            "API_UUID",
            "api.uuid",
            "__api.uuid"
        };

        // Try each property name in sequence
        for (String propertyName : propertyNames) {
            try {
                Object propertyValue = messageContext.getProperty(propertyName);
                if (propertyValue != null) {
                    String apiUuid = propertyValue.toString();
                    if (!apiUuid.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("[TREBLLE]:Found API UUID using property '" + propertyName + "': " + apiUuid);
                        }
                        return apiUuid;
                    }
                }
            } catch (Exception e) {
                log.warn("[TREBLLE]:Error reading property '" + propertyName + "': " + e.getMessage());
            }
        }

        // If all direct property lookups fail, log available properties for debugging
        if (log.isDebugEnabled()) {
            logAvailablePropertiesForUuid(messageContext);
        }

        log.warn("[TREBLLE]:Unable to determine API UUID. The 'internal_id' field will be null.");
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
            List<String> cached = apiMaskKeywordsCache.getIfPresent(apiUuid);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]:Per-API mask keywords cache hit for API " + apiUuid);
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
                log.debug("[TREBLLE]:Found per-API mask keywords from 'treblle_mask_keywords': " + maskKeywordsValue);
            }
        }

        // Try handler-level additionalProperties injected via velocity template setter (works on distributed gateways)
        if (maskKeywordsValue == null) {
            String value = getAdditionalProperties().getProperty("treblle_mask_keywords");
            if (value != null && !value.isEmpty()) {
                maskKeywordsValue = value;
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]:Found per-API mask keywords from handler additionalProperties: " + maskKeywordsValue);
                }
            }
        }

        // Try api.ut.additionalProperties map (fallback for some WSO2 versions)
        if (maskKeywordsValue == null) {
            Object utAdditionalProps = messageContext.getProperty("api.ut.additionalProperties");
            if (utAdditionalProps instanceof Map) {
                Object value = ((Map<String, Object>) utAdditionalProps).get("treblle_mask_keywords");
                if (value instanceof String && !((String) value).isEmpty()) {
                    maskKeywordsValue = (String) value;
                    if (log.isDebugEnabled()) {
                        log.debug("[TREBLLE]:Found per-API mask keywords from 'api.ut.additionalProperties': " + maskKeywordsValue);
                    }
                }
            }
        }

        if (maskKeywordsValue == null) {
            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]:No per-API mask keywords found for API " + (apiUuid != null ? apiUuid : "unknown"));
            }
            return null;
        }

        // Parse comma-separated keywords, trimming whitespace
        List<String> keywords = Arrays.stream(maskKeywordsValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

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
            Boolean cached = apiDisableResponseBodyCache.getIfPresent(apiUuid);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]:Disable response body cache hit for API " + apiUuid + ": " + cached);
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
                log.debug("[TREBLLE]:Found disable response body from 'treblle_disable_response_body': " + flagValue);
            }
        }

        // Try handler-level additionalProperties injected via velocity template setter (works on distributed gateways)
        if (flagValue == null) {
            String value = getAdditionalProperties().getProperty("treblle_disable_response_body");
            if (value != null && !value.isEmpty()) {
                flagValue = value;
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]:Found disable response body from handler additionalProperties: " + flagValue);
                }
            }
        }

        // Try api.ut.additionalProperties map (fallback for some WSO2 versions)
        if (flagValue == null) {
            Object utAdditionalProps = messageContext.getProperty("api.ut.additionalProperties");
            if (utAdditionalProps instanceof Map) {
                Object value = ((Map<String, Object>) utAdditionalProps).get("treblle_disable_response_body");
                if (value instanceof String && !((String) value).isEmpty()) {
                    flagValue = (String) value;
                    if (log.isDebugEnabled()) {
                        log.debug("[TREBLLE]:Found disable response body from 'api.ut.additionalProperties': " + flagValue);
                    }
                }
            }
        }

        boolean result = "true".equalsIgnoreCase(flagValue);

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
        log.debug("[TREBLLE]:Listing all MessageContext properties containing 'UUID', 'API', or 'IDENTIFIER':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("UUID") || upperKey.contains("API") ||
                    upperKey.contains("IDENTIFIER")) {

                    Object value = messageContext.getProperty(key);
                    log.debug("[TREBLLE]:  - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.debug("[TREBLLE]:  (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.debug("[TREBLLE]:Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("UUID") || upperKey.contains("API") ||
                    upperKey.contains("IDENTIFIER")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.debug("[TREBLLE]:  - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.debug("[TREBLLE]:  (No relevant properties found in Axis2 MessageContext)");
            }

        } catch (Exception e) {
            log.error("[TREBLLE]:Error logging available properties for UUID: " + e.getMessage(), e);
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
        log.debug("[TREBLLE]:Listing all MessageContext properties containing 'REST', 'API', 'RESOURCE', or 'PATTERN':");

        try {
            java.util.Set<String> propertyKeys = messageContext.getPropertyKeySet();
            int count = 0;

            for (String key : propertyKeys) {
                String upperKey = key.toUpperCase();
                if (upperKey.contains("REST") || upperKey.contains("API") ||
                    upperKey.contains("RESOURCE") || upperKey.contains("PATTERN") ||
                    upperKey.contains("URI") || upperKey.contains("TEMPLATE")) {

                    Object value = messageContext.getProperty(key);
                    log.debug("[TREBLLE]:  - " + key + " = " + value);
                    count++;
                }
            }

            if (count == 0) {
                log.debug("[TREBLLE]:  (No relevant properties found in MessageContext)");
            }

            // Also check Axis2 MessageContext properties
            org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

            log.debug("[TREBLLE]:Listing relevant Axis2 MessageContext properties:");
            int axis2Count = 0;

            java.util.Iterator<?> propertyNames = axis2MsgContext.getPropertyNames();
            while (propertyNames.hasNext()) {
                String key = String.valueOf(propertyNames.next());
                String upperKey = key.toUpperCase();

                if (upperKey.contains("REST") || upperKey.contains("API") ||
                    upperKey.contains("RESOURCE") || upperKey.contains("PATTERN") ||
                    upperKey.contains("URI") || upperKey.contains("TEMPLATE")) {

                    Object value = axis2MsgContext.getProperty(key);
                    log.debug("[TREBLLE]:  - " + key + " = " + value);
                    axis2Count++;
                }
            }

            if (axis2Count == 0) {
                log.debug("[TREBLLE]:  (No relevant properties found in Axis2 MessageContext)");
            }

        } catch (Exception e) {
            log.error("[TREBLLE]:Error logging available properties: " + e.getMessage(), e);
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
