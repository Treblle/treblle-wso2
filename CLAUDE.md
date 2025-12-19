# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Treblle Data Publisher extension for WSO2 API Manager. It integrates with WSO2's Global Synapse Handler to capture API request/response data and send it asynchronously to Treblle's monitoring service.

### Supported Versions

The project supports multiple WSO2 API Manager versions through Maven profiles:
- **3.2.x** - Profile: `wso2am-3.2` (APIM: 6.7.206, Synapse: 2.1.7-wso2v183)
- **4.0.x** - Profile: `wso2am-4.0` (APIM: 9.0.0, Synapse: 4.0.0-wso2v100)
- **4.1.x** - Profile: `wso2am-4.1` (APIM: 9.1.0, Synapse: 4.0.0-wso2v102)
- **4.2.x** - Profile: `wso2am-4.2` (APIM: 9.2.0, Synapse: 4.0.0-wso2v103)
- **4.3.x** - Profile: `wso2am-4.3` (APIM: 9.29.120, Synapse: 4.0.0-wso2v105) - **DEFAULT**
- **4.4.x** - Profile: `wso2am-4.4` (APIM: 9.31.0, Synapse: 4.0.0-wso2v106)

## Build and Test Commands

```bash
# Build for default version (4.3.x)
mvn clean install

# Build for specific WSO2 version
mvn clean install -P wso2am-3.2
mvn clean install -P wso2am-4.0
mvn clean install -P wso2am-4.1
mvn clean install -P wso2am-4.2
mvn clean install -P wso2am-4.3
mvn clean install -P wso2am-4.4

# Build all versions at once
mvn clean install -P wso2am-3.2,wso2am-4.0,wso2am-4.1,wso2am-4.2,wso2am-4.3,wso2am-4.4

# Run tests
mvn test

# Run specific test class
mvn test -Dtest=APILogHandlerTest
mvn test -Dtest=PublisherClientTest
```

The JAR artifact name includes the version suffix (e.g., `treblle-data-publisher-4.3.x-1.0.0.jar`).

## Architecture Overview

### Core Flow
1. **APILogHandler** (extends AbstractSynapseHandler) - Main entry point that intercepts API traffic through WSO2's synapse handler mechanism
   - `handleRequestInFlow()` - Captures request data (headers, body, IP, method, path, route_path)
   - `handleRequestOutFlow()` - Captures API name (internal_name) and API UUID (internal_id)
   - `handleResponseOutFlow()` - Creates TrebllePayload and enqueues it

2. **EventQueue** - Thread-safe bounded queue that holds events for asynchronous processing
   - Uses `LinkedBlockingQueue` with configurable size
   - Manages worker thread pool via `ExecutorService`
   - Drops events when queue is full (logs every 1000 drops)

3. **ParallelQueueWorker** - Worker threads that dequeue events and publish them
   - Runs in background, continuously polling the queue
   - Each worker calls `PublisherClient.publish()`

4. **PublisherClient** - HTTP client that sends masked payloads to Treblle
   - Masks sensitive keywords in headers/body before sending
   - Uses random load balancing across 3 default Treblle endpoints (or custom URL)
   - Retries once on 5xx errors with 1-second delay
   - No retry on 4xx errors (client errors)

5. **DataHolder** - Singleton that initializes the queue and stores configuration
   - Reads environment variables on startup
   - Manages tenant domain filtering

### Data Flow
```
WSO2 Request → APILogHandler (capture) → TrebllePayload (DTO) → EventQueue →
ParallelQueueWorker → PublisherClient (mask + send) → Treblle Service
```

### Key DTOs
- **TrebllePayload** - Root payload with api_key, sdk_token, internal_id, internal_name, version, sdk, and Data object
  - `internal_id` - The WSO2 API UUID captured from MessageContext properties (e.g., `API_UUID`, `api.uuid`)
  - `internal_name` - The WSO2 API name captured from MessageContext properties (e.g., `SYNAPSE_REST_API`, `API_NAME`)
- **Data** - Contains Request, Response, Server, Language, and RuntimeError list
- **Request** - Timestamp, IP, headers, body, method, URL, user agent, route_path
  - `route_path` - The API resource template pattern (e.g., `/users/{userId}/posts`) captured from `API_ELECTED_RESOURCE`
- **Response** - Status code, headers, body, size, load_time
- **Server** - IP, timezone, OS, software version

## Environment Variables

Required:
- `TREBLLE_SDK_TOKEN` - Authentication token
- `TREBLLE_API_KEY` - Project API key

Optional:
- `TREBLLE_GATEWAY_URL` - Custom endpoint (defaults to round-robin across 3 Treblle URLs)
- `TREBLLE_QUEUE_SIZE` - Queue capacity (default: 20000)
- `TREBLLE_WORKER_THREADS` - Number of publisher threads (default: 1)
- `ADDITIONAL_MASK_KEYWORDS` - Comma-separated sensitive field names to mask
- `TREBLLE_ENABLED_TENANT_DOMAINS` - Comma-separated tenant domains (default: all enabled)

## Deployment

The version-specific JAR artifact must be deployed to `<gateway>/repository/components/lib` (ensure it matches your WSO2 APIM version) and the handler must be registered in `deployment.toml`:

```toml
[synapse_handlers.treblle_publisher]
enabled=true
class="com.treblle.wso2publisher.handlers.APILogHandler"
```

## Important Notes for Version Support

- **Single Codebase**: All WSO2 versions share the same codebase; only dependency versions differ
- **Maven Profiles**: Version-specific builds are controlled via Maven profiles in `pom.xml`
- **API Compatibility**: The code is written to be compatible across all supported WSO2 versions
- **Testing**: When making changes, consider testing against multiple versions if dependencies are affected
- **Version Detection**: The extension doesn't detect the WSO2 version at runtime; it must be compiled with the correct profile

## Important Implementation Notes

- **Error Handling**: The handler always returns `true` from all handler methods to ensure API requests are never blocked, even if Treblle integration fails
- **Masking**: Default keywords include `password`, `pwd`, `secret`, `cc`, `card_number`, `ccv`, `ssn`, `credit_score`. Additional keywords can be added via environment variable
- **Content-Type**: Only `application/json` request/response bodies are captured; other content types are skipped
- **Retry Logic**: Uses `PublisherClientContextHolder.PUBLISH_ATTEMPTS` ThreadLocal for tracking retry attempts per thread
- **IP Detection**: Checks `X-FORWARDED-FOR` header first, falls back to remote address from Axis2 context
- **Thread Safety**: EventQueue uses atomic counters and blocking queues; worker threads handle interrupts gracefully for shutdown
- **Route Path**: Captures `API_ELECTED_RESOURCE` from MessageContext which contains the API resource template pattern in OpenAPI format (e.g., `/users/{userId}/posts` instead of `/users/12345/posts`). This field is stored in `data.request.route_path` and may be null if the property is not available in the WSO2 context.
- **API UUID (internal_id)**: Attempts to capture the WSO2 API UUID by trying multiple MessageContext property names (`API_UUID`, `api.uuid`, `__api.uuid`, `API_IDENTIFIER`, etc.) to maximize compatibility across different WSO2 versions. This field is stored at the **root level** of the TrebllePayload (alongside `api_key`, `sdk_token`, etc.) and may be null if none of the property lookups succeed. When null, debug logs will list all available properties containing 'UUID', 'API', or 'IDENTIFIER' to help identify the correct property name for your WSO2 version.
- **API Name (internal_name)**: Attempts to capture the WSO2 API name by trying multiple MessageContext property names (`SYNAPSE_REST_API`, `API_NAME`, `REST_API_NAME`, `api.name`, `REST_API_CONTEXT`, `API_CONTEXT`, etc.) to maximize compatibility across different WSO2 versions. This field is stored at the **root level** of the TrebllePayload (alongside `api_key`, `sdk_token`, etc.) and may be null if none of the property lookups succeed. When null, debug logs will list all available properties containing 'NAME', 'API', or 'CONTEXT' to help identify the correct property name for your WSO2 version.
