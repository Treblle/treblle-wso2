package com.treblle.wso2publisher.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

public class TrebllePayload {

  public static final int TREBLLE_VERSION = 22;
  private static final String SDK_NAME = "wso2";

  @JsonProperty("api_key")
  private String apiKey;

  @JsonProperty("sdk_token")
  private String sdkToken;

  @JsonProperty("internal_id")
  private String internalId;

  @JsonProperty("internal_name")
  private String internalName;

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
}
