# API Log Handler

A Custom Synapse Handler to Log API Request / Response in WSO2 API Manager platform.

> The branch contains the source code of the handler implemented for APIM v3.2.0. Please make a clone of this repo and update the dependencies and build the handler to support in other versions of the WSO2 API Manager.

Overview of Extension
> The extension retrieves data from Global Synapse Handler (https://ei.docs.wso2.com/en/latest/micro-integrator/develop/customizations/creating-synapse-handlers/) in the API Gateway  and creates a payload to send to Trebelle. The data is added onto a queue once received and is processed by a worker thread. The worker thread sends the data asynchronously to Trebelle, if the data is successfully sent, the data is removed from the queue. If the data is not successfully sent, the worker thread will attempt to send the data again, after 1 retry attempt, the event will be dropped.
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
  api_log_handler.class = "handlers.com.trebelle.ws02publisher.APILogHandler"
  ```

- Add the following in `<gateway>/repository/conf/log4j2.properties` for logging purposes
  
  > Following to enable the logs to populate under default `wso2carbon.log`. You can create a custom appender to log the entries to a separate log file.

  ```properties
  loggers = api-log-handler, AUDIT_LOG, ...

  logger.api-log-handler.name = handlers.com.trebelle.ws02publisher.APILogHandler
  logger.api-log-handler.level = DEBUG
  logger.api-log-handler.appenderRef.CARBON_LOGFILE.ref = CARBON_LOGFILE
  ```

- Before starting the WSO2 Server add the following environment variables.

MacOs
```
export TREBELLE_API_KEY=API-KEY
export TREBELLE_PROJECT_ID=Project-id
export TREBELLE_GATEWAY_URL="GW-URL(https://test.com)" 
export TREBELLE_QUEUE_SIZE = 20000
export TREBELLE_WORKER_THREADS = 1
export ADDITIONAL_MASK_KEYWORDS=testkey,Authorization,token
```
Windows
```
set TREBELLE_API_KEY=API-KEY
set TREBELLE_PROJECT_ID=Project-id
set TREBELLE_GATEWAY_URL="GW-URL(https://test.com)" 
set ADDITIONAL_MASK_KEYWORDS=testkey,Authorization,token
set TREBELLE_QUEUE_SIZE = 20000
set TREBELLE_WORKER_THREADS = 1
```