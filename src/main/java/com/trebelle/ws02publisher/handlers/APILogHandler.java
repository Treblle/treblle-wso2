package com.trebelle.ws02publisher.handlers;

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
import com.trebelle.ws02publisher.dto.Data;
import com.trebelle.ws02publisher.dto.Language;
import com.trebelle.ws02publisher.dto.OperatingSystem;
import com.trebelle.ws02publisher.dto.Request;
import com.trebelle.ws02publisher.dto.Response;
import com.trebelle.ws02publisher.dto.RuntimeError;
import com.trebelle.ws02publisher.dto.Server;
import com.trebelle.ws02publisher.dto.TrebllePayload;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class APILogHandler extends AbstractSynapseHandler {

    private static final String HEADER_TREBELLE_FORWARDED_FOR = "TREBELLE-FORWARDED-FOR";
    private static final String TREBELLE_REQ_HEADERS = "TREBELLE_REQ_HEADERS";
    private static final String TREBELLE_REQ_BODY = "TREBELLE_REQ_BODY";
    private static final String TREBELLE_REQ_PATH = "TREBELLE_REQ_PATH";
    private static final String TREBELLE_REQ_METHOD = "TREBELLE_REQ_METHOD";
    private static final String TREBELLE_API_NAME = "TREBELLE_API_NAME";
    private static final String TREBELLE_REQ_IP = "TREBELLE_REQ_IP";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    private static final Log log = LogFactory.getLog(APILogHandler.class);

    @Override
    public boolean handleRequestInFlow(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        Map headers = (Map) axis2MsgContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers == null) {
            log.error("Headers are null");
            return false;
        }
        Map<String, String> headersMap = new HashMap<>();
        for (Object key : headers.keySet()) {
            headersMap.put(key.toString(), headers.get(key).toString());
        }
        messageContext.setProperty(TREBELLE_REQ_HEADERS, headersMap);

        try {
            RelayUtils.buildMessage(axis2MsgContext);
        } catch (Exception e) {
            log.error(e.getMessage());
            return false;
        }

        String jsonPayloadToString = JsonUtil.jsonPayloadToString(axis2MsgContext);
        if (jsonPayloadToString == null) {
            log.error("JSON payload is null");
            return false;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper.readTree(jsonPayloadToString);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
            return false;
        }
        messageContext.setProperty(TREBELLE_REQ_BODY, jsonNode);

        String reqPath = (String) axis2MsgContext.getProperty("REST_URL_POSTFIX");
        if (reqPath == null) {
            log.error("Request path is null");
            return false;
        }
        messageContext.setProperty(TREBELLE_REQ_PATH, reqPath);

        String sourceIP = getSourceIP(axis2MsgContext, headers);
        if (sourceIP == null) {
            log.error("Source IP is null");
            return false;
        }
        messageContext.setProperty(TREBELLE_REQ_IP, sourceIP);

        String apiMethod = (String) axis2MsgContext.getProperty("HTTP_METHOD");
        if (apiMethod == null) {
            log.error("API method is null");
            return false;
        }
        messageContext.setProperty(TREBELLE_REQ_METHOD, apiMethod);

        return true;
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext messageContext) {

        String apiName = (String) messageContext.getProperty("SYNAPSE_REST_API");
        messageContext.setProperty(TREBELLE_API_NAME, apiName); // unique name can be used as uid

        return true;
    }

    @Override
    public boolean handleResponseInFlow(MessageContext messageContext) {
        return true;
    }

    @Override
    public boolean handleResponseOutFlow(MessageContext messageContext) {

        TrebllePayload payload = createPayload(messageContext);
        DataHolder.getInstance().getEventQueue().put(payload);
        return true;
    }

    private long getResponseTime(org.apache.synapse.MessageContext messageContext) {
        long responseTime = 0;
        try {
            long rtStartTime = 0;
            if (messageContext.getProperty(APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME) != null) {
                Object objRtStartTime = messageContext.getProperty(APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME);
                rtStartTime = (objRtStartTime == null ? 0 : Long.parseLong((String) objRtStartTime));
            }
            responseTime = System.currentTimeMillis() - rtStartTime;
            responseTime = responseTime * 1000;
        } catch (Exception e) {
            log.error("Error getResponseTime -  " + e.getMessage(), e);
        }
        return responseTime;
    }

    private String getSourceIP(org.apache.axis2.context.MessageContext axis2Context, Map headers) {
        String clientIP;
        String xForwardedForHeader = (String) headers.get(HEADER_TREBELLE_FORWARDED_FOR);
        if (!StringUtils.isEmpty(xForwardedForHeader)) {
            clientIP = xForwardedForHeader;
            int index = xForwardedForHeader.indexOf(',');
            if (index > -1) {
                clientIP = clientIP.substring(0, index);
            }
        } else {
            clientIP = (String) axis2Context.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);
        }

        return clientIP;
    }

    private TrebllePayload createPayload(org.apache.synapse.MessageContext messageContext) {

        Map reqHeaders = (Map) messageContext.getProperty(TREBELLE_REQ_HEADERS);
        if (reqHeaders == null) {
            log.error("Request headers are null");
            return null;
        }

        JsonNode reqBody = (JsonNode) messageContext.getProperty(TREBELLE_REQ_BODY);
        if (reqBody == null) {
            log.error("Request body is null");
            return null;
        }

        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();
        if (axis2MsgContext == null) {
            log.error("Axis2 message context is null");
            return null;
        }

        // logging
        for (Object key : reqHeaders.keySet()) {
            log.info("header: " + key.toString() + " - " + reqHeaders.get(key).toString());
        }

        final Language language = new Language();
        language.setName("java");
        language.setVersion(System.getProperty("java.version"));

        // Create Server

        final OperatingSystem os = new OperatingSystem();
        os.setName(System.getProperty("os.name"));
        os.setArchitecture(System.getProperty("os.arch"));
        os.setRelease(System.getProperty("os.version"));
        String ipAddress = "";
        try {
            InetAddress inetAddress = InetAddress.getLocalHost();
            ipAddress = inetAddress.getHostAddress();
            log.info("IP Address: " + ipAddress);
        } catch (UnknownHostException e) {
            log.error("Unknown host exception: " + e.getMessage());
            return null;
        }
        if (ipAddress.isEmpty()) {
            log.error("IP address is empty");
            return null;
        }
        final Server server = new Server();
        server.setIp(ipAddress);
        server.setTimezone(TimeZone.getDefault().getID());
        server.setProtocol("");
        server.setOs(os);
        server.setSoftware("WSO2 v3.2");
        server.setSignature("sig");
        server.setProtocol("http");
        server.setEncoding(Charset.defaultCharset().name());

        // Create Request

        final Request request = new Request();
        request.setTimestamp(ZonedDateTime.now(ZoneOffset.UTC).format(DATE_TIME_FORMATTER));

        String reqIp = (String) messageContext.getProperty(TREBELLE_REQ_IP);
        if (reqIp == null) {
            log.error("Request IP is null");
            return null;
        }
        request.setIp(reqIp);

        String userAgent = (String) reqHeaders.get("User-Agent");
        if (userAgent == null) {
            log.error("User-Agent header is null");
            return null;
        }
        request.setUserAgent(userAgent);

        String method = (String) messageContext.getProperty(TREBELLE_REQ_METHOD);
        if (method == null) {
            log.error("Request method is null");
            return null;
        }
        request.setMethod(method);

        String gwURL = System.getenv("TREBELLE_GATEWAY_URL");
        if (gwURL == null) {
            log.error("Gateway URL is null");
            return null;
        }
        String reqURL = (String) messageContext.getProperty(TREBELLE_REQ_PATH);
        if (reqURL == null) {
            log.error("Request URL is null");
            return null;
        }
        if (!reqURL.startsWith("/")) {
            reqURL = "/" + reqURL;
        }
        String reqPath = gwURL + reqURL;
        request.setUrl(reqPath);

        Map<String, String> requestHeaderMap = new HashMap<>();
        for (Object key : reqHeaders.keySet()) {
            requestHeaderMap.put(key.toString(), reqHeaders.get(key).toString());
        }
        request.setHeaders(requestHeaderMap);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode reqJsonNode = null;
        try {
            reqJsonNode = objectMapper.readTree(reqBody.toString());
        } catch (JsonProcessingException e) {
            log.error("Error processing JSON: " + e.getMessage());
            return null;
        }
        request.setBody(reqJsonNode);

        // Create Response

        final Data data = new Data();
        final Response response = new Response();

        String responseCodeValue = axis2MsgContext.getProperty("HTTP_SC").toString();
        int responseCode = 500;
        try {
            responseCode = Integer.parseInt(responseCodeValue);
        } catch (NumberFormatException e) {
            log.error("Error parsing response code: " + responseCodeValue);
        }

        if (responseCode >= 400 && responseCode < 600) {

            final RuntimeError runtimeError = new RuntimeError();

            // check errors for 404
            Object errorMessageObj = axis2MsgContext.getProperty("ERROR_MESSAGE");
            String errorType = errorMessageObj instanceof String ? (String) errorMessageObj : null;

            Object errorDetailObj = axis2MsgContext.getProperty("ERROR_DETAIL");
            String errorDetail = errorDetailObj instanceof String ? (String) errorDetailObj : null;

            log.info(responseCodeValue + " - " + errorType + " - " + errorDetail);

            runtimeError.setType(errorType);
            runtimeError.setMessage(errorDetail);
            runtimeError.setSource("onError");

            List<RuntimeError> runtimeErrors = new ArrayList<>(2);
            runtimeErrors.add(runtimeError);
            data.setErrors(runtimeErrors);
        }

        response.setCode(responseCode);

        try {
            RelayUtils.buildMessage(((Axis2MessageContext) messageContext).getAxis2MessageContext());
        } catch (Exception e) {
            log.error("Error building message: " + e.getMessage());
            return null;
        }

        String jsonPayloadToString = JsonUtil
                .jsonPayloadToString(((Axis2MessageContext) messageContext).getAxis2MessageContext());
        if (jsonPayloadToString == null) {
            log.error("JSON payload to string is null");
            return null;
        }
        ObjectMapper objectMapper1 = new ObjectMapper();

        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper1.readTree(jsonPayloadToString);
        } catch (JsonProcessingException e) {
            log.error("Error processing JSON: " + e.getMessage());
            return null;
        }

        response.setBody(jsonNode);

        byte[] responseBody = jsonPayloadToString.getBytes();
        response.setSize((long) responseBody.length);

        Map headersMap = (Map) ((Axis2MessageContext) messageContext).getAxis2MessageContext()
                .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headersMap == null) {
            log.error("Response headers are null");
            return null;
        }

        Map<String, String> responseHeaderMap = new HashMap<>();
        for (Object key : headersMap.keySet()) {
            responseHeaderMap.put(key.toString(), headersMap.get(key).toString());
        }
        response.setHeaders(responseHeaderMap);
        response.setLoadTime((double) getResponseTime(messageContext));

        data.setServer(server);
        data.setLanguage(language);
        data.setRequest(request);
        data.setResponse(response);

        TrebllePayload payload = new TrebllePayload();
        payload.setData(data);

        return payload;
    }

}
