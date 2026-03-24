package com.treblle.wso2publisher.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class TrebllePayload {

  public static final int TREBLLE_VERSION = 23;
  private static final String SDK_NAME = "wso2";

  @JsonProperty("api_key")
  private String apiKey;

  @JsonProperty("sdk_token")
  private String sdkToken;

  @JsonProperty("internal_id")
  private String internalId;

  @JsonProperty("internal_name")
  private String internalName;

  @JsonProperty("tenant_id")
  private String tenantId;

  @JsonProperty("app_name")
  private String appName;

  @JsonProperty("app_id")
  private String appId;

  @JsonProperty("user_id")
  private String userId;

  @JsonProperty("api_publisher")
  private String apiPublisher;

  @JsonIgnore
  private List<String> perApiMaskKeywords;

  private Data data;

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getSdkToken() {
    return sdkToken;
  }

  public void setSdkToken(String sdkToken) {
    this.sdkToken = sdkToken;
  }

  @JsonGetter("version")
  public int getVersion() {
    return TREBLLE_VERSION;
  }

  @JsonGetter("sdk")
  public String getSdk() {
    return SDK_NAME;
  }

  public Data getData() {
    return data;
  }

  public void setData(Data data) {
    this.data = data;
  }

  public String getInternalId() {
    return internalId;
  }

  public void setInternalId(String internalId) {
    this.internalId = internalId;
  }

  public String getInternalName() {
    return internalName;
  }

  public void setInternalName(String internalName) {
    this.internalName = internalName;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getAppId() {
    return appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getApiPublisher() {
    return apiPublisher;
  }

  public void setApiPublisher(String apiPublisher) {
    this.apiPublisher = apiPublisher;
  }

  public List<String> getPerApiMaskKeywords() {
    return perApiMaskKeywords;
  }

  public void setPerApiMaskKeywords(List<String> perApiMaskKeywords) {
    this.perApiMaskKeywords = perApiMaskKeywords;
  }
}
