package com.treblle.wso2publisher.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

public class TrebllePayload {

    private static final String TREBLLE_VERSION = "0.1";
    private static final String SDK_NAME = "wso2";

    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("api_id")
    private String apiId;

    private Data data;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiId() {
        return apiId;
    }

    public void setApiId(String apiId) {
        this.apiId = apiId;
    }

    @JsonGetter("version")
    public String getVersion() {
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
}
