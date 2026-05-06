package com.treblle.wso2publisher.dto;

import java.util.Collections;
import java.util.List;

public class Data {

  private Server server;
  private Language language;
  private Request request;
  private Response response;
  private Metadata metadata;
  private List<RuntimeError> errors = Collections.emptyList();

  public Server getServer() {
    return server;
  }

  public void setServer(Server server) {
    this.server = server;
  }

  public Language getLanguage() {
    return language;
  }

  public void setLanguage(Language language) {
    this.language = language;
  }

  public Request getRequest() {
    return request;
  }

  public void setRequest(Request request) {
    this.request = request;
  }

  public Response getResponse() {
    return response;
  }

  public void setResponse(Response response) {
    this.response = response;
  }

  public Metadata getMetadata() {
    return metadata;
  }

  public void setMetadata(Metadata metadata) {
    this.metadata = metadata;
  }

  public List<RuntimeError> getErrors() {
    return errors;
  }

  public void setErrors(List<RuntimeError> errors) {
    this.errors = errors;
  }
}
