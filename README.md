# API Log Handler

A Custom Synapse Handler to Log API Request / Response in WSO2 API Manager platform.

> The branch contains the source code of the handler implemented for APIM v3.2.0. Please make a clone of this repo and update the dependencies and build the handler to support in other versions of the WSO2 API Manager.

## Build

Execute the following command from the root directory of the project to build

```sh
mvn clean install
```

## Usage

### Configuration

- Copy the built JAR artifact and place it inside the `<gateway>/repository/components/lib` directory and start the server to load the required classes
- Add the following configuration at the **beginning** of the `<gateway>/repository/conf/deployment.toml` to engage the API Log Handler

  ```toml
  enabled_global_handlers = ["api_log_handler"]

  [synapse_handlers]
  api_log_handler.name = "MockHandler"
  api_log_handler.class = "com.sample.handlers.APILogHandler"
  ```

- Add the following in `<gateway>/repository/conf/log4j2.properties` for logging purposes
  
  > Following to enable the logs to populate under default `wso2carbon.log`. You can create a custom appender to log the entries to a separate log file.
  
  ```properties
  loggers = api-log-handler, AUDIT_LOG, ...

  logger.api-log-handler.name = com.sample.handlers.APILogHandler
  logger.api-log-handler.level = DEBUG
  logger.api-log-handler.appenderRef.CARBON_LOGFILE.ref = CARBON_LOGFILE
  ```

- Before starting the WSO2 Server add the following environment variables.

MacOs
```
export X_API_KEY=API-KEY
export X_PROJECT_ID=Project-id
export X_GATEWAY_URL="GW-URL(https://test.com)" 
```
Windows
```
set X_API_KEY=API-KEY
set X_PROJECT_ID=Project-id
set X_GATEWAY_URL="GW-URL(https://test.com)" 
```