package com.treblle.wso2publisher.dto;

import java.util.List;

public class TrebllePayload {

  public static final int TREBLLE_VERSION = 24;
  private static final String SDK_NAME = "wso2";

  private String apiKey;

  private String sdkToken;

  private String internalId;

  private String internalName;

  private String tenantId;

  private String appName;

  private String appId;

  private String userId;

  private String apiPublisher;

  private List<String> perApiMaskKeywords;

  private boolean disableResponseBody;

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

  public int getVersion() {
    return TREBLLE_VERSION;
  }

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

  public boolean isDisableResponseBody() {
    return disableResponseBody;
  }

  public void setDisableResponseBody(boolean disableResponseBody) {
    this.disableResponseBody = disableResponseBody;
  }
}
