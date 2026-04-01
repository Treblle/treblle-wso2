# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Treblle Data Publisher extension for WSO2 API Manager. It integrates with WSO2's API Handler chain (`AbstractHandler`) to capture API request/response data and send it asynchronously to Treblle's monitoring service. By running inside the handler chain (after `APIAuthenticationHandler`), the handler has access to enriched properties like tenant domain, application name, subscriber info, and API publisher.

### Supported Versions

The project supports multiple WSO2 API Manager versions through Maven profiles:
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
mvn clean install -P wso2am-4.0
mvn clean install -P wso2am-4.1
mvn clean install -P wso2am-4.2
mvn clean install -P wso2am-4.3
mvn clean install -P wso2am-4.4

# Build all versions at once
mvn clean install -P wso2am-4.0,wso2am-4.1,wso2am-4.2,wso2am-4.3,wso2am-4.4

# Run tests
mvn test

# Run specific test class
mvn test -Dtest=APILogHandlerTest
mvn test -Dtest=PublisherClientTest
```

The JAR artifact name includes the version suffix (e.g., `treblle-data-publisher-4.3.x-1.0.0.jar`).

## Architecture Overview

### Core Flow
1. **APILogHandler** (extends AbstractHandler) - Main entry point that intercepts API traffic inside WSO2's API handler chain
   - `handleRequest()` - Captures request data (headers, body, IP, method, path, route_path), API name, API UUID, and enriched properties (tenant domain, application name/ID, user ID, API publisher)
   - `handleResponse()` - Creates TrebllePayload and enqueues it

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
   - No retry logic - events are dropped if the first attempt fails

5. **DataHolder** - Singleton that initializes the queue and stores configuration
   - Reads environment variables on startup
   - Manages tenant domain filtering

### Data Flow
```
WSO2 Request → APILogHandler (capture) → TrebllePayload (DTO) → EventQueue →
ParallelQueueWorker → PublisherClient (mask + send) → Treblle Service
```

### Key DTOs
- **TrebllePayload** - Root payload with api_key, sdk_token, internal_id, internal_name, tenant_id, app_name, app_id, user_id, api_publisher, version, sdk, and Data object
  - `internal_id` - The WSO2 API UUID captured from MessageContext properties (e.g., `API_UUID`, `api.uuid`)
  - `internal_name` - The WSO2 API name captured from MessageContext properties (e.g., `SYNAPSE_REST_API`, `API_NAME`)
  - `tenant_id` - The WSO2 tenant domain (e.g., `carbon.super`) from `tenant.info.domain`
  - `app_name` - The consuming application name from `APPLICATION_NAME`
  - `app_id` - The consuming application ID from `APPLICATION_ID`
  - `user_id` - The end user name from `END_USER_NAME`
  - `api_publisher` - The API publisher/owner from `API_PUBLISHER`
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
- `ADDITIONAL_MASK_KEYWORDS` - Comma-separated sensitive field names to mask (global, applies to all APIs)
- `TREBLLE_ENABLED_TENANT_DOMAINS` - Comma-separated tenant domains (default: all enabled)

## Per-API Masking Keywords

In addition to global masking via `ADDITIONAL_MASK_KEYWORDS`, API publishers can define per-API masking keywords through WSO2 API custom properties in the Publisher portal.

### Configuration
Set the custom property `treblle_mask_keywords` on an API in the WSO2 Publisher portal with a comma-separated list of field names to mask (e.g., `ssn,dob,account_number`).

### How It Works
1. `handleRequest()` reads the `treblle_mask_keywords` property via `getPerApiMaskKeywords()`, which tries multiple sources in order:
   - Flat MessageContext properties: `api.ut.treblle_mask_keywords`, `treblle_mask_keywords`
   - MessageContext maps: `additionalProperties`, `api.ut.additionalProperties`
   - **WSO2 API Manager registry** (last resort): fetches the full API object via `APIManagerFactory → APIProvider.getAPIbyUUID()` and reads `api.getAdditionalProperties().get("treblle_mask_keywords")`. This is the path that works for custom properties set in the Publisher portal.
2. Parsed keywords are cached by API UUID in an LRU cache (max 1000 entries) for performance — the registry lookup only happens once per API
3. Keywords travel on the `TrebllePayload` via a `@JsonIgnore` transient field (never serialized to JSON)
4. `PublisherClient.buildRequestBodyForTrebllePayload()` applies per-API keywords after global masking
5. Per-API keywords are merged with (not replacing) global defaults

## Deployment

### Step 1: Deploy JAR
The version-specific JAR artifact must be deployed to `<APIM_HOME>/repository/components/lib` (ensure it matches your WSO2 APIM version).

### Step 2: Configure Handler
Add the Treblle handler to the velocity template file at:
```
<APIM_HOME>/repository/resources/api_templates/velocity_template.xml
```

Add the handler **after** the SchemaValidator in the handlers section:

```xml
## check and set enable schema validation
#if($enableSchemaValidation)
<handler class="org.wso2.carbon.apimgt.gateway.handlers.security.SchemaValidator"/>
#end
<handler class="com.treblle.wso2publisher.handlers.APILogHandler"/>
</handlers>
```

This ensures the handler runs after authentication and usage handlers, giving access to all enriched properties (tenant domain, application info, user data, API publisher).

## Troubleshooting and Debug Logging

To enable comprehensive debug logging that shows ALL MessageContext properties available in your WSO2 environment:

### Option 1: Runtime Configuration (WSO2 Management Console)
1. Log in to WSO2 API Manager Management Console (https://localhost:9443/carbon)
2. Navigate to **Configure > Logging**
3. Add a new logger:
   - Logger Name: `com.treblle.wso2publisher.handlers.APILogHandler`
   - Log Level: `DEBUG`
   - Additivity: `true`
4. Click **Update**

### Option 2: Configuration File (Persistent)
Edit `<APIM_HOME>/repository/conf/log4j2.properties` and add:

```properties
logger.treblle.name = com.treblle.wso2publisher.handlers.APILogHandler
logger.treblle.level = DEBUG
logger.treblle.additivity = false
logger.treblle.appenderRef.CARBON_LOGFILE.ref = CARBON_LOGFILE
```

Then add the logger to the loggers list:
```properties
loggers = ..., treblle
```

Restart WSO2 API Manager after making changes to `log4j2.properties`.

### Debug Output
When debug mode is enabled, each API request will log:
- **All Synapse MessageContext properties** (sorted alphabetically)
- **All Axis2 MessageContext properties** (sorted alphabetically)
- Property names, values (truncated if > 200 chars), and data types
- Clear section headers: `TREBLLE DEBUG: ALL MESSAGE CONTEXT PROPERTIES`

This is invaluable for:
- Identifying which property names are available in your WSO2 version
- Troubleshooting missing API UUID, API name, or route path values
- Understanding what enriched properties are populated by upstream handlers
- Finding the correct property names for custom integrations

**Note**: Debug logging generates verbose output. Only enable it temporarily for troubleshooting, not in production.

## Important Notes for Version Support

- **Single Codebase**: All WSO2 versions share the same codebase; only dependency versions differ
- **Maven Profiles**: Version-specific builds are controlled via Maven profiles in `pom.xml`
- **API Compatibility**: The code is written to be compatible across all supported WSO2 versions
- **Testing**: When making changes, consider testing against multiple versions if dependencies are affected
- **Version Detection**: The extension doesn't detect the WSO2 version at runtime; it must be compiled with the correct profile

## Important Implementation Notes

- **Handler Type**: Uses `AbstractHandler` (`org.apache.synapse.rest.AbstractHandler`) which runs inside the API handler chain, after `APIAuthenticationHandler` and `APIMgtUsageHandler`. This provides access to enriched MessageContext properties that are not available with `AbstractSynapseHandler`.
- **Error Handling**: The handler always returns `true` from all handler methods to ensure API requests are never blocked, even if Treblle integration fails
- **Masking**: Default keywords include `password`, `pwd`, `secret`, `cc`, `card_number`, `ccv`, `ssn`, `credit_score`. Additional keywords can be added via environment variable
- **Content-Type**: Only `application/json` request/response bodies are captured; other content types are skipped
- **No Retry Logic**: Failed events are dropped immediately without retry attempts to avoid blocking worker threads
- **IP Detection**: Checks `X-FORWARDED-FOR` header first, falls back to remote address from Axis2 context
- **OPTIONS Filtering**: CORS preflight OPTIONS requests are skipped and not tracked by Treblle
- **Thread Safety**: EventQueue uses atomic counters and blocking queues; worker threads handle interrupts gracefully for shutdown
- **Route Path**: Captures `API_ELECTED_RESOURCE` from MessageContext which contains the API resource template pattern in OpenAPI format (e.g., `/users/{userId}/posts` instead of `/users/12345/posts`). This field is stored in `data.request.route_path` and may be null if the property is not available in the WSO2 context.
- **API UUID (internal_id)**: Attempts to capture the WSO2 API UUID by trying multiple MessageContext property names (`API_UUID`, `api.uuid`, `__api.uuid`, `API_IDENTIFIER`, etc.) to maximize compatibility across different WSO2 versions. This field is stored at the **root level** of the TrebllePayload (alongside `api_key`, `sdk_token`, etc.) and may be null if none of the property lookups succeed. When null, debug logs will list all available properties containing 'UUID', 'API', or 'IDENTIFIER' to help identify the correct property name for your WSO2 version.
- **API Name (internal_name)**: Attempts to capture the WSO2 API name by trying multiple MessageContext property names (`SYNAPSE_REST_API`, `API_NAME`, `REST_API_NAME`, `api.name`, `REST_API_CONTEXT`, `API_CONTEXT`, etc.) to maximize compatibility across different WSO2 versions. This field is stored at the **root level** of the TrebllePayload (alongside `api_key`, `sdk_token`, etc.) and may be null if none of the property lookups succeed. When null, debug logs will list all available properties containing 'NAME', 'API', or 'CONTEXT' to help identify the correct property name for your WSO2 version.
- **Enriched Properties**: Because the handler runs after `APIAuthenticationHandler`, it captures additional context: `tenant_id` (tenant domain), `app_name` (application name), `app_id` (application ID), `user_id` (end user), and `api_publisher` (API owner). These are stored at the root level of the TrebllePayload and may be null if the upstream handlers did not populate them.
- **Per-API Masking**: API publishers can set `treblle_mask_keywords` as a custom property on their API in the WSO2 Publisher portal. The handler reads this property by trying multiple lookup strategies, falling back to the WSO2 API Manager registry via `APIManagerFactory → APIProvider.getAPIbyUUID()` as the last resort (this is what actually works for Publisher portal custom properties). Results are cached by API UUID in an LRU cache so the registry is only hit once per API. The per-API keywords are transported on `TrebllePayload.perApiMaskKeywords` (a `@JsonIgnore` field, never serialized) and merged with global masking keywords at publish time.
- **Debug Mode Logging**: When WSO2 debug logging is enabled for the handler (via `log4j2.properties` or runtime configuration), the handler logs ALL available MessageContext properties from both Synapse and Axis2 contexts on every request. This comprehensive logging includes property names, values (truncated if over 200 chars), and data types, making it invaluable for troubleshooting property availability across different WSO2 versions. The debug output is clearly marked with `TREBLLE DEBUG` headers for easy filtering in log files.
