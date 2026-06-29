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

    // Runtime-constant system properties cached at class load to avoid per-request lock contention
    private static final String JAVA_VERSION = System.getProperty("java.version");
    private static final String OS_NAME = System.getProperty("os.name");
    private static final String OS_ARCH = System.getProperty("os.arch");
    private static final String OS_VERSION = System.getProperty("os.version");
    private static final String WSO2_SOFTWARE;
    static {
        String ver = System.getProperty("carbon.product.version");
        WSO2_SOFTWARE = ver != null ? "WSO2 " + ver : "WSO2 API Manager";
    }

    private static final int API_CONFIG_CACHE_MAX_SIZE = 1000;
    private static final Cache<String, PerApiConfig> apiConfigCache = CacheBuilder.newBuilder()
        .maximumSize(API_CONFIG_CACHE_MAX_SIZE)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();
    private static volatile String serverIP;

    private static final Log log = LogFactory.getLog(APILogHandler.class);

    protected Properties getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(String additionalPropertiesJsonXmlEscaped) {
        log.warn("[TREBLLE]:setAdditionalProperties called with: " + additionalPropertiesJsonXmlEscaped);
        this.additionalProperties.clear();
        if (additionalPropertiesJsonXmlEscaped != null && !additionalPropertiesJsonXmlEscaped.trim().isEmpty()) {
            String additionalPropertiesJson = StringEscapeUtils.unescapeXml(additionalPropertiesJsonXmlEscaped);
            try {
                JSONObject jsonObject = new JSONObject(additionalPropertiesJson);
                this.additionalProperties.putAll(PropertyUtils.toProperties(jsonObject));
                log.warn("[TREBLLE]:additionalProperties parsed OK, keys: " + this.additionalProperties.stringPropertyNames());
            } catch (JSONException e) {
                log.warn("[TREBLLE]:Unable to parse additionalProperties JSON - " + e.getMessage());
            }
        }
    }

    // Called by Synapse when velocity template injects: <property name="treblleMaskKeywords" value="..."/>
    public void setTreblleMaskKeywords(String value) {
        log.warn("[TREBLLE]:setTreblleMaskKeywords called with: " + value);
        if (value != null && !value.trim().isEmpty()) {
            this.additionalProperties.setProperty("treblle_mask_keywords", value.trim());
        }
    }

    // Called by Synapse when velocity template injects: <property name="treblleDisableResponseBody" value="..."/>
    public void setTreblleDisableResponseBody(String value) {
        log.warn("[TREBLLE]:setTreblleDisableResponseBody called with: " + value);
        if (value != null && !value.trim().isEmpty()) {
            this.additionalProperties.setProperty("treblle_disable_response_body", value.trim());
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

            // Retrieve and set the HTTP method (reuse httpMethod already read above)
            messageContext.setProperty(TREBLLE_REQ_METHOD, httpMethod);

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

            // Capture per-API config (mask keywords + disable response body) — single cache lookup
            PerApiConfig perApiConfig = getOrLoadApiConfig(messageContext, apiUuid);
            List<String> perApiMaskKeywords = perApiConfig.maskKeywords;
            if (perApiMaskKeywords != null && !perApiMaskKeywords.isEmpty()) {
                messageContext.setProperty(TREBLLE_PER_API_MASK_KEYWORDS, perApiMaskKeywords);
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]: Per-API mask keywords for API " + apiUuid + ": " + perApiMaskKeywords);
                }
            }
            messageContext.setProperty(TREBLLE_DISABLE_RESPONSE_BODY, perApiConfig.disableResponseBody);
            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]: Disable response body for API " + (apiUuid != null ? apiUuid : "unknown") + ": " + perApiConfig.disableResponseBody);
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("[TREBLLE]: Performance handleRequest: %.2f ms", (System.nanoTime() - reqStart) / 1_000_000.0));
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
                log.debug(String.format("[TREBLLE]: Performance handleResponse.createPayload: %.2f ms", (System.nanoTime() - payloadStart) / 1_000_000.0));
                log.debug("[TREBLLE]:Payload created, enqueueing...");
            }
            // Add the payload to the event queue for processing
            final long enqueueStart = log.isDebugEnabled() ? System.nanoTime() : 0;
            DataHolder.getInstance().getEventQueue().put(payload);
            if (log.isDebugEnabled()) {
                log.debug(String.format("[TREBLLE]: Performance handleResponse.enqueue: %.2f ms", (System.nanoTime() - enqueueStart) / 1_000_000.0));
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
        language.setVersion(JAVA_VERSION);

        // Create and initialize the OperatingSystem object
        final OperatingSystem os = new OperatingSystem();
        os.setName(OS_NAME);
        os.setArchitecture(OS_ARCH);
        os.setRelease(OS_VERSION);

        // Create and initialize the Server object
        final Server server = new Server();
        server.setIp(getServerIpAddress());
        server.setTimezone(TimeZone.getDefault().getID());
        server.setOs(os);

        server.setSoftware(WSO2_SOFTWARE);
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
        response.setSize(responseBodyRaw != null ? (long) responseBodyRaw.length() : 0L);
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
            Object value = headers.get(key);
            if (key != null && value != null) {
                headersMap.put(key.toString(), value.toString());
            }
        }
        return headersMap;
    }

    private String getMessageBodyRaw(MessageContext messageContext, Map<String, String> headers) {

        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        // Determine content type case-insensitively BEFORE building the message.
        String contentType = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                contentType = entry.getValue().toLowerCase();
                break;
            }
        }

        // Never build/consume binary payloads. In the WSO2 pass-through transport a binary
        // body (e.g. image/png, application/pdf, application/octet-stream) is streamed straight
        // to the client without being parsed into memory. Calling RelayUtils.buildMessage() on
        // it drains the pass-through pipe and forces a re-serialization that strips/corrupts the
        // attachment. We skip body capture for non-text content and let the stream pass through
        // untouched — request/response metadata (code, headers, size, timing) is still logged.
        if (contentType != null && !isTextBasedContentType(contentType)) {
            if (log.isDebugEnabled()) {
                log.debug("[TREBLLE]: Skipping body capture for binary content type: " + contentType);
            }
            return null;
        }

        try {
            RelayUtils.buildMessage(axis2MsgContext);
        } catch (Exception e) {
            log.error("[TREBLLE]: Error building message: " + e.getMessage());
            return null;
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

    /**
     * Returns true for content types whose body is safe to build and capture as text.
     * Anything else (images, PDFs, octet-stream, audio/video, fonts, etc.) is treated as
     * binary and must not be passed to RelayUtils.buildMessage() in the pass-through flow.
     */
    private boolean isTextBasedContentType(String contentType) {
        return contentType.contains("json")
                || contentType.contains("xml")
                || contentType.contains("text/")
                || contentType.contains("x-www-form-urlencoded")
                || contentType.contains("multipart/form-data");
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
        Map<String, String> enabledDomains = DataHolder.getInstance().getEnabledTenantDomains();
        if (enabledDomains.isEmpty()) {
            // No filter configured → all tenant domains are enabled (documented default)
            return true;
        }
        String tenantDomain = (String) messageContext.getProperty("tenant.info.domain");
        if (tenantDomain == null) {
            log.warn("[TREBLLE]: Tenant domain is null. Skipping the handler.");
            return false;
        }
        return enabledDomains.containsKey(tenantDomain);
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
                } else if (log.isDebugEnabled()) {
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
     * Load per-API config (mask keywords + disable-response-body flag) from WSO2 custom properties,
     * backed by a single cache keyed by API UUID to avoid re-parsing on every request.
     */
    private PerApiConfig getOrLoadApiConfig(MessageContext messageContext, String apiUuid) {
        if (apiUuid != null) {
            PerApiConfig cached = apiConfigCache.getIfPresent(apiUuid);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[TREBLLE]: Per-API config cache hit for API " + apiUuid);
                }
                return cached;
            }
        }

        // Resolve mask keywords
        String maskKeywordsValue = resolveMaskKeywordsValue(messageContext, apiUuid);
        List<String> maskKeywords = null;
        if (maskKeywordsValue != null) {
            maskKeywords = Arrays.stream(maskKeywordsValue.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (maskKeywords.isEmpty()) {
                maskKeywords = null;
            }
        }

        // Resolve disable response body
        String flagValue = null;
        Object directFlag = messageContext.getProperty("treblle_disable_response_body");
        if (directFlag instanceof String && !((String) directFlag).isEmpty()) {
            flagValue = (String) directFlag;
        }
        if (flagValue == null) {
            String handlerValue = getAdditionalProperties().getProperty("treblle_disable_response_body");
            if (handlerValue != null && !handlerValue.isEmpty()) {
                flagValue = handlerValue;
            }
        }
        boolean disableResponseBody = "true".equalsIgnoreCase(flagValue);

        PerApiConfig config = new PerApiConfig(maskKeywords, disableResponseBody);
        if (apiUuid != null) {
            apiConfigCache.put(apiUuid, config);
        }
        return config;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String resolveMaskKeywordsValue(MessageContext messageContext, String apiUuid) {
        // Approach 1: direct Synapse MessageContext property
        Object directProp = messageContext.getProperty("treblle_mask_keywords");
        if (directProp instanceof String && !((String) directProp).isEmpty()) {
            return (String) directProp;
        }

        // Approach 2: handler-level Properties injected via velocity template setter
        String handlerPropsValue = getAdditionalProperties().getProperty("treblle_mask_keywords");
        if (handlerPropsValue != null && !handlerPropsValue.isEmpty()) {
            return handlerPropsValue;
        }

        // Approach 3: api.ut.additionalProperties JSON string or Map
        Object apiUtProps = messageContext.getProperty("api.ut.additionalProperties");
        if (apiUtProps instanceof String && !((String) apiUtProps).isEmpty()) {
            try {
                String value = new JSONObject((String) apiUtProps).optString("treblle_mask_keywords", null);
                if (value != null && !value.isEmpty()) return value;
            } catch (JSONException e) {
                log.debug("[TREBLLE]: Failed to parse api.ut.additionalProperties JSON: " + e.getMessage());
            }
        } else if (apiUtProps instanceof Map) {
            Object value = ((Map) apiUtProps).get("treblle_mask_keywords");
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        }

        // Approach 4: additionalProperties JSON string or Map
        Object mcAdditionalProps = messageContext.getProperty("additionalProperties");
        if (mcAdditionalProps instanceof String && !((String) mcAdditionalProps).isEmpty()) {
            try {
                String value = new JSONObject((String) mcAdditionalProps).optString("treblle_mask_keywords", null);
                if (value != null && !value.isEmpty()) return value;
            } catch (JSONException e) {
                log.debug("[TREBLLE]: Failed to parse additionalProperties JSON: " + e.getMessage());
            }
        } else if (mcAdditionalProps instanceof Map) {
            Object value = ((Map) mcAdditionalProps).get("treblle_mask_keywords");
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        }

        // Approach 5: api.ut.* prefixed property directly
        Object apiUtDirect = messageContext.getProperty("api.ut.treblle_mask_keywords");
        if (apiUtDirect instanceof String && !((String) apiUtDirect).isEmpty()) {
            return (String) apiUtDirect;
        }

        return null;
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

    private static final class PerApiConfig {
        final List<String> maskKeywords; // null = not configured
        final boolean disableResponseBody;

        PerApiConfig(List<String> maskKeywords, boolean disableResponseBody) {
            this.maskKeywords = maskKeywords;
            this.disableResponseBody = disableResponseBody;
        }
    }

}
