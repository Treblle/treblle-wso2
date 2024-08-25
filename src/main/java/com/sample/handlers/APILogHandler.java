package com.sample.handlers;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.*;

import org.apache.axis2.util.URL;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.AbstractSynapseHandler;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.impl.gatewayartifactsynchronizer.exception.ArtifactSynchronizerException;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sample.dto.Data;
import com.sample.dto.Language;
import com.sample.dto.OperatingSystem;
import com.sample.dto.Request;
import com.sample.dto.Response;
import com.sample.dto.RuntimeError;
import com.sample.dto.Server;
import com.sample.dto.TrebllePayload;

import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.snmp4j.agent.mo.snmp.NotificationLogMib.NlmLogVariableValueTypeEnum.ipAddress;

public class APILogHandler extends AbstractSynapseHandler {

    private static final String HEADER_X_FORWARDED_FOR = "X-FORWARDED-FOR";
    private static final String X_REQ_HEADERS = "X_REQ_HEADERS";
    private static final String X_REQ_BODY = "X_REQ_BODY";
    private static final String X_REQ_PATH = "X_REQ_PATH";
    private static final String X_REQ_METHOD = "X_REQ_METHOD";
    private static final String X_API_NAME = "X_API_NAME";
    private static final String X_REQ_IP = "X_REQ_IP";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] BASE_URLS = {
            "https://rocknrolla.treblle.com",
            "https://punisher.treblle.com",
            "https://sicario.treblle.com"
    };


    private static final Log log = LogFactory.getLog(APILogHandler.class);

    @Override
    public boolean handleRequestInFlow(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        Map headers = (Map) ((Axis2MessageContext) messageContext).getAxis2MessageContext()
                .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        messageContext.setProperty(X_REQ_HEADERS, headers);

        for (Object key : headers.keySet()) {
            log.info("header: " + key.toString() + " - " + headers.get(key).toString());
        }

        // Build the payload
        try {
            RelayUtils.buildMessage(((Axis2MessageContext) messageContext).getAxis2MessageContext());
        } catch (Exception e) {
            log.error(e.getMessage());
        }

        // Getting the json payload to string
        String jsonPayloadToString = JsonUtil
                .jsonPayloadToString(((Axis2MessageContext) messageContext).getAxis2MessageContext());
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper.readTree(jsonPayloadToString);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
        messageContext.setProperty(X_REQ_BODY, jsonNode);

        // doesnt retrieve gateway url, hence add to env in shell
        String reqPath = (String) axis2MsgContext.getProperty("REST_URL_POSTFIX");
        messageContext.setProperty(X_REQ_PATH, reqPath); // for options call

        String sourceIP = getSourceIP(axis2MsgContext, headers);
        messageContext.setProperty(X_REQ_IP, sourceIP);

        String apiMethod = (String) axis2MsgContext.getProperty("HTTP_METHOD");
        messageContext.setProperty(X_REQ_METHOD, apiMethod);

        return true;
    }

    @Override
    public boolean handleRequestOutFlow(MessageContext messageContext) {

        String apiName = (String) messageContext.getProperty("SYNAPSE_REST_API");
        String apiRestReqFullPath = (String) messageContext.getProperty("REST_FULL_REQUEST_PATH");
        messageContext.setProperty(X_API_NAME, apiName); // unique name can be used as uid

        messageContext.setProperty(X_REQ_PATH, apiRestReqFullPath);

        return true;
    }

    @Override
    public boolean handleResponseInFlow(MessageContext messageContext) {
        return true;
    }

    @Override
    public boolean handleResponseOutFlow(MessageContext messageContext) {

        TrebllePayload payload = createPayload(messageContext);
        String randomBaseUrl = getRandomBaseUrl();
        log.info("Sending to Treblle ### " + randomBaseUrl);
        CloseableHttpResponse response3 = maskAndSendPayload(payload, randomBaseUrl);
        if (response3.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            log.info("Deal dn ###");
            return true;
        } else {
            log.error("Error ### " + response3.getStatusLine().toString());
        }

        log.info("Sent to Treblle");
        return true;
    }


    private static String getRandomBaseUrl() {
        Random random = new Random();
        int index = random.nextInt(BASE_URLS.length);
        return BASE_URLS[index];
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
            responseTime = responseTime*1000;
        } catch (Exception e) {
            log.error("Error getResponseTime -  " + e.getMessage(), e);
        }
        return responseTime;
    }

    private String getSourceIP(org.apache.axis2.context.MessageContext axis2Context, Map headers) {
        String clientIP;
        String xForwardedForHeader = (String) headers.get(HEADER_X_FORWARDED_FOR);
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

        Map reqHeaders = (Map) messageContext.getProperty(X_REQ_HEADERS);
        JsonNode reqBody = (JsonNode) messageContext.getProperty(X_REQ_BODY);
        org.apache.axis2.context.MessageContext axis2MsgContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();

        // logging
        for (Object key : reqHeaders.keySet()) {
            log.info("header: " + key.toString() + " - " + reqHeaders.get(key).toString());
        }

        final Language language = new Language();
        language.setName("java");
        language.setVersion(System.getProperty("java.version"));

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
            e.printStackTrace();
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

        //Request

        final Request request = new Request();
        request.setTimestamp(ZonedDateTime.now(ZoneOffset.UTC).format(DATE_TIME_FORMATTER));

        request.setIp(messageContext.getProperty(X_REQ_IP).toString());
        request.setUserAgent((String) reqHeaders.get("User-Agent"));

        String method = (String) messageContext.getProperty(X_REQ_METHOD);
//        if (method == null) {
//            method = "OPTIONS";
//        }
        request.setMethod(method);

        String gwURL = System.getenv("X_GATEWAY_URL");
        String reqURL = messageContext.getProperty(X_REQ_PATH).toString();
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
            log.error(e.getMessage());
        }
        request.setBody(reqJsonNode);


        //Response

        final Response response = new Response();
        response.setCode((Integer) axis2MsgContext.getProperty("HTTP_SC"));

        try {
            RelayUtils.buildMessage(((Axis2MessageContext) messageContext).getAxis2MessageContext());
        } catch (Exception e) {
            log.error(e.getMessage());
        }

        String jsonPayloadToString = JsonUtil
                .jsonPayloadToString(((Axis2MessageContext) messageContext).getAxis2MessageContext());
        ObjectMapper objectMapper1 = new ObjectMapper();

        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper1.readTree(jsonPayloadToString);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }

        response.setBody(jsonNode);

        byte[] responseBody = jsonPayloadToString.getBytes();
        response.setSize((long) responseBody.length);

        Map headersMap = (Map) ((Axis2MessageContext) messageContext).getAxis2MessageContext()
                .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);

        Map<String, String> responseHeaderMap = new HashMap<>();
        for (Object key : headersMap.keySet()) {
            responseHeaderMap.put(key.toString(), headersMap.get(key).toString());
        }
        response.setHeaders(responseHeaderMap);
        response.setLoadTime((double) getResponseTime(messageContext));

        final Data data = new Data();
        data.setServer(server);
        data.setLanguage(language);
        data.setRequest(request);
        data.setResponse(response);

        TrebllePayload payload = new TrebllePayload();
        String apiKey = System.getenv("X_API_KEY");
        String projectId = System.getenv("X_PROJECT_ID");
        payload.setApiKey(apiKey);
        payload.setProjectId(projectId);
        payload.setData(data);

        return payload;
    }

    private CloseableHttpResponse maskAndSendPayload(TrebllePayload payload, String baseUrl) {

        final List<RuntimeError> errors = new ArrayList<>(2);
        if (!errors.isEmpty()) {
            payload.getData().setErrors(errors);
        }

        URL serviceEndpointURL = new URL(baseUrl);
        HttpClient httpClient = APIUtil.getHttpClient(serviceEndpointURL.getPort(),
                serviceEndpointURL.getProtocol());
        HttpPost httpPost = new HttpPost(baseUrl);
        httpPost.setHeader("x-api-key", payload.getApiKey());
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        StringEntity params;
        try {
            org.json.JSONObject requestBody = buildRequestBodyForTrebllePayload(payload);
            log.info("$$$$ body - " + requestBody);
            params = new StringEntity(requestBody.toString());
            httpPost.setEntity(params);
            return APIUtil.executeHTTPRequest(httpPost, httpClient);
        } catch (IOException | ArtifactSynchronizerException e) {
            log.error(e.getMessage());
        }

        return null;
    }

    private org.json.JSONObject buildRequestBodyForTrebllePayload(TrebllePayload trebllePayload) {

        org.json.JSONObject requestBody = new org.json.JSONObject();
        requestBody.put("api_key", trebllePayload.getApiKey());
        requestBody.put("project_id", trebllePayload.getProjectId());
        requestBody.put("sdk", "wso2");
        requestBody.put("version", "0.1");

        org.json.JSONObject data = new org.json.JSONObject();
        data.put("language", new org.json.JSONObject(trebllePayload.getData().getLanguage()));

        org.json.JSONObject request = new org.json.JSONObject();
        request.put("timestamp", trebllePayload.getData().getRequest().getTimestamp());
        request.put("ip", trebllePayload.getData().getRequest().getIp());
        request.put("user_agent", trebllePayload.getData().getRequest().getUserAgent());
        request.put("method", trebllePayload.getData().getRequest().getMethod());
        request.put("url", trebllePayload.getData().getRequest().getUrl());
        request.put("headers", new org.json.JSONObject(trebllePayload.getData().getRequest().getHeaders()));
        request.put("body", trebllePayload.getData().getRequest().getBody());
        data.put("request", request);

        org.json.JSONObject response = new org.json.JSONObject();
        response.put("code", trebllePayload.getData().getResponse().getCode());
        response.put("body", trebllePayload.getData().getResponse().getBody());
        response.put("size", trebllePayload.getData().getResponse().getSize());
        response.put("headers", new org.json.JSONObject(trebllePayload.getData().getResponse().getHeaders()));
        response.put("load_time", trebllePayload.getData().getResponse().getLoadTime());
        data.put("response", response);

        data.put("server", new org.json.JSONObject(trebllePayload.getData().getServer()));
        data.put("errors", new org.json.JSONArray(trebllePayload.getData().getErrors()));
        requestBody.put("data", data);

        return requestBody;
    }
}
