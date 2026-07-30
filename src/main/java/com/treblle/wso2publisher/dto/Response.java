package com.treblle.wso2publisher.dto;

import java.util.Map;

public class Response {

  private Map<String, String> headers;
  private Integer code;
  private Long size;

  private Double loadTime;

  private String bodyRaw;

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
  }

  public Integer getCode() {
    return code;
  }

  public void setCode(Integer code) {
    this.code = code;
  }

  public Long getSize() {
    return size;
  }

  public void setSize(Long size) {
    this.size = size;
  }

  public Double getLoadTime() {
    return loadTime;
  }

  public void setLoadTime(Double loadTime) {
    this.loadTime = loadTime;
  }

  public String getBodyRaw() {
    return bodyRaw;
  }

  public void setBodyRaw(String bodyRaw) {
    this.bodyRaw = bodyRaw;
  }
}
