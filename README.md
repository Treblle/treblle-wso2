# Treblle - API Intelligence Platform

[![Treblle API Intelligence](https://github.com/user-attachments/assets/b268ae9e-7c8a-4ade-95da-b4ac6fce6eea)](https://treblle.com)

[Website](http://treblle.com/) • [Documentation](https://docs.treblle.com/) • [Pricing](https://treblle.com/pricing)

Treblle is an API intelligence platfom that helps developers, teams and organizations understand their APIs from a single integration point.

---

## Treblle WSO2 SDK

| WSO2 API Manager Version | Status | Maven Profile | APIM Version | Synapse Version |
|-------------------------|---------|---------------|--------------|-----------------|
| 4.0.x | ✅ Supported | `wso2am-4.0` | 9.0.0 | 4.0.0-wso2v100 |
| 4.1.x | ✅ Supported | `wso2am-4.1` | 9.1.0 | 4.0.0-wso2v102 |
| 4.2.x | ✅ Supported | `wso2am-4.2` | 9.2.0 | 4.0.0-wso2v103 |
| 4.3.x | ✅ Supported (Default) | `wso2am-4.3` | 9.29.120 | 4.0.0-wso2v105 |
| 4.4.x | ✅ Supported | `wso2am-4.4` | 9.31.0 | 4.0.0-wso2v106 |

**Note:** The extension uses Maven profiles to support multiple WSO2 versions. By default, it builds for WSO2 APIM 4.3.x.

## Overview

This extension integrates with WSO2's API Handler chain (`AbstractHandler`) to intercept API requests and responses inside the gateway. By running after the `APIAuthenticationHandler` and `APIMgtUsageHandler`, it has access to enriched context including tenant domain, application info, subscriber data, and API publisher. It operates asynchronously to ensure zero impact on API performance.

### How It Works

1. **Request Capture** - Intercepts incoming API requests, capturing headers, body, IP, method, path, and enriched properties (tenant, application, user, publisher)
2. **Response Capture** - Captures response data including status code, headers, body, and load time
3. **Async Processing** - Adds captured data to a bounded in-memory queue (non-blocking)
4. **Worker Threads** - Background threads dequeue events and send them to Treblle
5. **Data Masking** - Automatically masks sensitive fields before transmission

### Key Features

- **Non-blocking**: Never blocks or slows down API requests, even on failures
- **Thread-safe**: Uses concurrent queues and atomic operations
- **Load-balanced**: Distributes traffic across multiple Treblle endpoints
- **Sensitive data masking**: Automatically redacts passwords, tokens, credit cards, etc.
- **Multi-tenant support**: Filter by tenant domains
- **Enriched context**: Captures application name, user ID, API publisher, and tenant domain
- **Configurable**: Queue size, worker threads, custom endpoints, and masking rules

## Prerequisites

- WSO2 API Manager 4.0.x or higher
- Java 8 or higher
- Maven 3.x (for building from source)
- Treblle account with SDK token and API key ([Get started](https://treblle.com))

## Build from Source

### Building for Default Version (WSO2 APIM 4.3.x)

Execute the following command from the root directory:

```sh
mvn clean install
```

The JAR artifact will be created in the `target/` directory as `treblle-data-publisher-4.3.x-1.0.0.jar`

### Building for Specific WSO2 Versions

Use Maven profiles to build for specific WSO2 API Manager versions:

```sh
# For WSO2 APIM 4.0.x
mvn clean install -P wso2am-4.0

# For WSO2 APIM 4.1.x
mvn clean install -P wso2am-4.1

# For WSO2 APIM 4.2.x
mvn clean install -P wso2am-4.2

# For WSO2 APIM 4.3.x (default)
mvn clean install -P wso2am-4.3

# For WSO2 APIM 4.4.x
mvn clean install -P wso2am-4.4
```

The JAR artifact name will include the version suffix (e.g., `treblle-data-publisher-4.0.x-1.0.0.jar`).

### Build All Versions at Once

To build artifacts for all supported versions:

```sh
mvn clean install -P wso2am-4.0,wso2am-4.1,wso2am-4.2,wso2am-4.3,wso2am-4.4
```

**Important:** Ensure you deploy the correct JAR artifact that matches your WSO2 API Manager version.

## Installation

### Step 1: Deploy the JAR

Copy the built JAR artifact that matches your WSO2 version to your API Manager gateway:

```sh
# Example for WSO2 APIM 4.3.x (default)
cp target/treblle-data-publisher-4.3.x-1.0.0.jar <APIM_HOME>/repository/components/lib/

# For other versions, use the appropriate JAR file
# cp target/treblle-data-publisher-4.0.x-1.0.0.jar <APIM_HOME>/repository/components/lib/
# cp target/treblle-data-publisher-4.1.x-1.0.0.jar <APIM_HOME>/repository/components/lib/
```

Replace `<APIM_HOME>` with your WSO2 API Manager installation directory.

### Step 2: Configure the Handler

Add the Treblle handler to `<APIM_HOME>/repository/resources/conf/templates/repository/conf/synapse-config/velocity_template.xml`.

Place it **after** `APIMgtUsageHandler` to ensure the handler has access to enriched properties (tenant domain, application info, user data):

```xml
<handler class="com.treblle.wso2publisher.handlers.APILogHandler"/>
```

### Step 3: Configure Logging (Optional but Recommended)

Add the following to `<APIM_HOME>/repository/conf/log4j2.properties`:

**Note:** This enables Treblle extension logs in the default `wso2carbon.log`. You can create a custom appender for separate log files.

  ```properties
  loggers = treblle_publisher, AUDIT_LOG, ...

  logger.treblle_publisher.name = com.treblle.wso2publisher
  logger.treblle_publisher.level = INFO
  logger.treblle_publisher.appenderRef.CARBON_LOGFILE.ref = CARBON_LOGFILE
  ```

### Step 4: Set Environment Variables

Before starting the WSO2 API Manager, configure the required environment variables.

**macOS / Linux:**
```bash
export TREBLLE_SDK_TOKEN=your-sdk-token
export TREBLLE_API_KEY=your-api-key
export TREBLLE_GATEWAY_URL="https://your-gateway.com"  # Optional
export TREBLLE_QUEUE_SIZE=20000                         # Optional
export TREBLLE_WORKER_THREADS=1                         # Optional
export ADDITIONAL_MASK_KEYWORDS=customToken,apiSecret   # Optional
export TREBLLE_ENABLED_TENANT_DOMAINS=carbon.super      # Optional
```

**Windows:**
```cmd
set TREBLLE_SDK_TOKEN=your-sdk-token
set TREBLLE_API_KEY=your-api-key
set TREBLLE_GATEWAY_URL=https://your-gateway.com
set TREBLLE_QUEUE_SIZE=20000
set TREBLLE_WORKER_THREADS=1
set ADDITIONAL_MASK_KEYWORDS=customToken,apiSecret
set TREBLLE_ENABLED_TENANT_DOMAINS=carbon.super
```

### Step 5: Start WSO2 API Manager

Start your WSO2 API Manager gateway:

```sh
cd <APIM_HOME>/bin
./api-manager.sh  # or api-manager.bat on Windows
```

Check the logs to verify the extension loaded successfully. You should see Treblle-related log entries in `wso2carbon.log`.

## Configuration Reference

| Environment Variable | Description | Required | Default |
|---------------------|-------------|----------|---------|
| `TREBLLE_SDK_TOKEN` | Your Treblle project SDK token | **Yes** | - |
| `TREBLLE_API_KEY` | Your Treblle project API key | **Yes** | - |
| `TREBLLE_GATEWAY_URL` | Custom Treblle endpoint URL | No | Auto load-balanced endpoints |
| `TREBLLE_QUEUE_SIZE` | Maximum in-memory queue size for events | No | `20000` |
| `TREBLLE_WORKER_THREADS` | Number of background worker threads | No | `1` |
| `ADDITIONAL_MASK_KEYWORDS` | Additional fields to mask (comma-separated) | No | See below |
| `TREBLLE_ENABLED_TENANT_DOMAINS` | Tenant domains to monitor (comma-separated) | No | All domains |

### Default Masked Keywords

The following keywords are automatically masked in headers and request/response bodies:
- `password`, `pwd`, `secret`, `password_confirmation`
- `cc`, `card_number`, `ccv`
- `ssn`, `credit_score`

Use `ADDITIONAL_MASK_KEYWORDS` to add more (e.g., `Authorization,X-API-Key,token`).

### Per-API Masking Keywords

In addition to global masking, you can define masking keywords on a per-API basis through WSO2 API custom properties. This allows each API to have its own set of sensitive fields masked, on top of the global defaults.

**How to configure:**

1. Open the WSO2 Publisher portal
2. Navigate to your API and go to **Properties**
3. Add a custom property:
   - **Name:** `treblle_mask_keywords`
   - **Value:** Comma-separated field names (e.g., `ssn,dob,account_number`)
4. Save and re-deploy the API

The per-API keywords are merged with (not replacing) the global defaults. They are cached by API UUID for performance and are never included in the JSON payload sent to Treblle.

### Custom Endpoint Configuration

**Load Balancing (Default):**

If `TREBLLE_GATEWAY_URL` is NOT set, the extension automatically load-balances across Treblle's endpoints:
- `https://rocknrolla.treblle.com`
- `https://punisher.treblle.com`
- `https://sicario.treblle.com`

**Custom Endpoint:**

If `TREBLLE_GATEWAY_URL` is set, all data is sent to your specified endpoint:
```bash
export TREBLLE_GATEWAY_URL="https://custom-treblle.your-domain.com"
```

Use cases: On-premise Treblle installations, custom routing, regional endpoints, or development testing.

## Troubleshooting

### Extension not loading

**Check logs** in `<APIM_HOME>/repository/logs/wso2carbon.log`:
```
grep -i treblle wso2carbon.log
```

**Verify JAR location:**
```sh
ls -la <APIM_HOME>/repository/components/lib/treblle-data-publisher-*.jar

# Ensure the version matches your WSO2 APIM version
```

**Verify velocity_template.xml configuration** — ensure the handler entry is placed after `APIMgtUsageHandler`.

### No data in Treblle dashboard

1. **Verify environment variables are set:**
   ```sh
   echo $TREBLLE_SDK_TOKEN
   echo $TREBLLE_API_KEY
   ```

2. **Check for errors in logs:**
   ```sh
   grep -i "treblle.*error" wso2carbon.log
   ```

3. **Verify tenant domain filtering** (if using `TREBLLE_ENABLED_TENANT_DOMAINS`):
   - Ensure your API's tenant domain is in the allowed list
   - Default tenant is usually `carbon.super`

4. **Check content type** - Only `application/json` requests/responses are captured

### Queue full errors

If you see "Event queue is full" errors:

1. **Increase queue size:**
   ```bash
   export TREBLLE_QUEUE_SIZE=50000
   ```

2. **Add more worker threads:**
   ```bash
   export TREBLLE_WORKER_THREADS=2
   ```

3. **Check network connectivity** to Treblle endpoints

### Memory issues

If experiencing memory pressure:

1. Reduce `TREBLLE_QUEUE_SIZE` (default 20,000)
2. Reduce `TREBLLE_WORKER_THREADS` (default 1)
3. Use `TREBLLE_ENABLED_TENANT_DOMAINS` to filter which APIs are monitored

## Development

### Running Tests

```sh
mvn test
```

### Running a Specific Test

```sh
mvn test -Dtest=APILogHandlerTest
mvn test -Dtest=PublisherClientTest
```


## Getting Help

If you continue to experience issues:

1. Enable `debug: true` and check console output
2. Verify your SDK token and API key are correct in Treblle dashboard
3. Test with a simple endpoint first
4. Check [Treblle documentation](https://docs.treblle.com) for the latest updates
5. Contact support at <https://treblle.com> or email support@treblle.com

## Support

If you have problems of any kind feel free to reach out via <https://treblle.com> or email support@treblle.com and we'll do our best to help you out.

## License

Copyright 2025, Treblle Inc. Licensed under the MIT license:
http://www.opensource.org/licenses/mit-license.php
