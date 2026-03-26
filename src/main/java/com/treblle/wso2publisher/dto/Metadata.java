package com.treblle.wso2publisher.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Metadata {

  @JsonProperty("api_version")
  private String apiVersion;

  @JsonProperty("user-id")
  private String userId;

  private String publisher;

  @JsonProperty("customer_ip")
  private String customerIp;

  private String tenant;

  private String host;

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = apiVersion;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getPublisher() {
    return publisher;
  }

  public void setPublisher(String publisher) {
    this.publisher = publisher;
  }

  public String getCustomerIp() {
    return customerIp;
  }

  public void setCustomerIp(String customerIp) {
    this.customerIp = customerIp;
  }

  public String getTenant() {
    return tenant;
  }

  public void setTenant(String tenant) {
    this.tenant = tenant;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }
}
