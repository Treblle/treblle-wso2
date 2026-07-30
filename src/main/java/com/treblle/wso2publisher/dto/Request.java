package com.treblle.wso2publisher.dto;

import java.util.Map;

public class Request {

  private String timestamp;
  private String ip;
  private String url;

  private String userAgent;

  private String method;
  private Map<String, String> headers;
  private String bodyRaw;

  private String routePath;

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public String getIp() {
    return ip;
  }

  public void setIp(String ip) {
    this.ip = ip;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
  }

  public String getBodyRaw() {
    return bodyRaw;
  }

  public void setBodyRaw(String bodyRaw) {
    this.bodyRaw = bodyRaw;
  }

  public String getRoutePath() {
    return routePath;
  }

  public void setRoutePath(String routePath) {
    this.routePath = routePath;
  }
}
