# Treblle Data Publisher

## Overview of Extension

The extension retrieves data from Global Synapse Handler (https://ei.docs.wso2.com/en/latest/micro-integrator/develop/customizations/creating-synapse-handlers/) in the API Gateway  and creates a payload to send to Treblle. The data is added onto a queue once received and is processed by a worker thread. The worker thread sends the data asynchronously to Treblle, if the data is successfully sent, the data is removed from the queue. If the data is not successfully sent, the worker thread will attempt to send the data again, after 1 retry attempt, the event will be dropped.

## Build the source code

Execute the following command from the root directory of the project to build

```sh
mvn clean install
```

## Usage

### Configuration

- Copy the built JAR artifact and place it inside the `<gateway>/repository/components/lib` directory and start the server to load the required classes.
- Add the following configuration at the **beginning** of the `<gateway>/repository/conf/deployment.toml` to engage the API Log Handler

  ```toml
  [synapse_handlers.treblle_publisher]
  enabled=true
  class="com.treblle.wso2publisher.handlers.APILogHandler"
  ```


- Add the following in `<gateway>/repository/conf/log4j2.properties` for logging purposes
  
  > Following to enable the logs to populate under default `wso2carbon.log`. You can create a custom appender to log the entries to a separate log file.

  ```properties
  loggers = treblle_publisher, AUDIT_LOG, ...

  logger.treblle_publisher.name = com.treblle.wso2publisher
  logger.treblle_publisher.level = INFO
  logger.treblle_publisher.appenderRef.CARBON_LOGFILE.ref = CARBON_LOGFILE
  ```

- Before starting the WSO2 Server add the following environment variables.

  
  MacOs
  ```
  export TREBLLE_API_KEY=API-KEY
  export TREBLLE_GATEWAY_URL="https://test.com" 
  export TREBLLE_QUEUE_SIZE=20000
  export TREBLLE_WORKER_THREADS=1
  export ADDITIONAL_MASK_KEYWORDS=testkey,Authorization,token
  export TREBLLE_ENABLED_TENANT_DOMAINS=carbon.super,abc.com
  ```
  Windows
  ```
  set TREBLLE_API_KEY=API-KEY
  set TREBLLE_GATEWAY_URL="https://test.com" 
  set ADDITIONAL_MASK_KEYWORDS=testkey,Authorization,token
  set TREBLLE_QUEUE_SIZE=20000
  set TREBLLE_WORKER_THREADS=1
  set TREBLLE_ENABLED_TENANT_DOMAINS=carbon.super,abc.com
  ```

  - Definitions

  ```
  TREBLLE_API_KEY=<API Key of the Treblle Project>
  TREBLLE_GATEWAY_URL=<WSO2 API Manager Gateway URL> 
  TREBLLE_QUEUE_SIZE=<Messages queue size>
  TREBLLE_WORKER_THREADS=<Number of worker threads for publishing data>
  ADDITIONAL_MASK_KEYWORDS=<Masking keywords such as header names and body parameters>
  TREBLLE_ENABLED_TENANT_DOMAINS<Treblle Publishing enabled tenant domains>
  ```

