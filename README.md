# Treblle SDK for WSO2 API Manager 4.3.0

[![Treblle](https://treblle.com/images/logo.svg)](https://treblle.com)

Treblle is a lightweight SDK that helps Engineering and Product teams build, ship & maintain REST based APIs faster.

## Features

- ✅ Real-time API monitoring and observability
- ✅ Automatic request/response logging
- ✅ Field-level data masking with recursive support
- ✅ Query parameter tracking and merging
- ✅ Automatic sensitive header masking (`Authorization`, `X-API-Key`)
- ✅ Base64 image detection and handling
- ✅ IPv4 address extraction and validation
- ✅ Asynchronous event publishing with retry mechanism
- ✅ Zero performance impact on user requests
- ✅ Error-safe implementation (SDK errors never affect user traffic)
- ✅ Multi-tenant support
- ✅ Configurable worker threads and queue size

## How It Works

The Treblle WSO2 SDK integrates with WSO2 API Manager through the Global Synapse Handler mechanism. It captures API requests and responses, processes them asynchronously, and sends the data to Treblle's ingestion endpoints.

**Architecture:**
1. **Request Flow**: Captures incoming requests (headers, body, query params, IP, timestamp)
2. **Response Flow**: Captures outgoing responses (headers, body, status code, load time)
3. **Asynchronous Processing**: Events are queued and processed by worker threads
4. **Field Masking**: Sensitive data is masked before transmission
5. **Retry Logic**: Failed requests are retried with exponential backoff
6. **Error Isolation**: SDK errors are caught and logged without affecting API traffic

## Installation

### 1. Build from Source

```bash
mvn clean install
```

This creates a JAR file in the `target/` directory.

### 2. Deploy to WSO2

Copy the built JAR artifact to your WSO2 Gateway:

```bash
cp target/treblle-wso2-publisher-*.jar <WSO2_HOME>/repository/components/lib/
```

### 3. Configure Synapse Handler

Add the following configuration at the **beginning** of `<WSO2_HOME>/repository/conf/deployment.toml`:

```toml
[synapse_handlers.treblle_publisher]
enabled = true
class = "com.treblle.wso2publisher.handlers.APILogHandler"
```

### 4. Configure Logging

Add the following to `<WSO2_HOME>/repository/conf/log4j2.properties`:

```properties
# Add 'treblle_publisher' to the loggers list
loggers = treblle_publisher, AUDIT_LOG, ...

# Configure Treblle logger
logger.treblle_publisher.name = com.treblle.wso2publisher
logger.treblle_publisher.level = INFO
logger.treblle_publisher.appenderRef.CARBON_LOGFILE.ref = CARBON_LOGFILE
```

### 5. Set Environment Variables

Before starting the WSO2 server, configure the required environment variables.

**Linux/MacOS:**

```bash
export TREBLLE_SDK_TOKEN="your_sdk_token_here"
export TREBLLE_API_KEY="your_api_key_here"
export TREBLLE_GATEWAY_URL="https://your-gateway.example.com"
export TREBLLE_ENABLED_TENANT_DOMAINS="carbon.super"
```

**Windows:**

```cmd
set TREBLLE_SDK_TOKEN=your_sdk_token_here
set TREBLLE_API_KEY=your_api_key_here
set TREBLLE_GATEWAY_URL=https://your-gateway.example.com
set TREBLLE_ENABLED_TENANT_DOMAINS=carbon.super
```

## Configuration

### Required Configuration

| Variable | Description | Example |
|----------|-------------|---------|
| `TREBLLE_SDK_TOKEN` | Your Treblle SDK token (get it from [treblle.com](https://treblle.com)) | `your_sdk_token_here` |
| `TREBLLE_API_KEY` | Your Treblle API key (get it from [treblle.com](https://treblle.com)) | `your_api_key_here` |
| `TREBLLE_GATEWAY_URL` | Your WSO2 API Gateway URL | `https://gateway.example.com` |

### Optional Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `TREBLLE_ENABLED_TENANT_DOMAINS` | Comma-separated list of tenant domains to monitor | `carbon.super` |
| `TREBLLE_QUEUE_SIZE` | Size of the event queue | `20000` |
| `TREBLLE_WORKER_THREADS` | Number of worker threads for async publishing | `1` |
| `ADDITIONAL_MASK_KEYWORDS` | Additional fields to mask (comma-separated) | See below |

### Default Masked Fields

The following fields are masked by default:

```
password, pwd, secret, password_confirmation, cc, card_number,
ccv, ssn, credit_score, api_key
```

**Sensitive headers (always masked):**
```
authorization, x-api-key
```

### Adding Custom Masked Fields

```bash
export ADDITIONAL_MASK_KEYWORDS="custom_token,secret_key,private_data"
```

## Field Masking

The SDK includes a powerful recursive field masker that protects sensitive data.

### Masking Features

1. **Recursive Masking**: Works at any nesting level (objects, arrays, nested objects in arrays)
2. **Case-Insensitive**: Matches `Password`, `PASSWORD`, `password` identically
3. **Length-Preserving**: Replaces each character with `*` (e.g., `secret123` → `*********`)
4. **Base64 Image Detection**: Replaces base64 images with `"base64 encoded images are too big to process"`
5. **Automatic Header Masking**: Always masks `Authorization` and `X-API-Key` headers

### Masking Examples

**Simple Field:**
```json
// Before
{ "password": "MyP@ssw0rd" }

// After
{ "password": "**********" }
```

**Nested Objects:**
```json
// Before
{
  "user": {
    "name": "John",
    "credentials": {
      "password": "secret",
      "api_key": "key123"
    }
  }
}

// After
{
  "user": {
    "name": "John",
    "credentials": {
      "password": "******",
      "api_key": "******"
    }
  }
}
```

**Arrays:**
```json
// Before
{
  "users": [
    { "name": "User1", "password": "pass1" },
    { "name": "User2", "password": "pass2" }
  ]
}

// After
{
  "users": [
    { "name": "User1", "password": "*****" },
    { "name": "User2", "password": "*****" }
  ]
}
```

**Headers:**
```json
// Before
{
  "Authorization": "Bearer token123456",
  "X-API-Key": "secret-key-abc"
}

// After
{
  "Authorization": "********************",
  "X-API-Key": "**************"
}
```

## Payload Structure

The SDK sends the following payload structure to Treblle:

```json
{
  "sdk_token": "your_sdk_token",
  "api_key": "your_api_key",
  "internal_id": "api-uuid",
  "internal_name": "APIName",
  "sdk": "wso2",
  "version": 20,
  "data": {
    "server": {
      "ip": "192.168.1.100",
      "timezone": "UTC",
      "software": "WSO2 v4.3",
      "protocol": "HTTP",
      "os": {
        "name": "Linux",
        "release": "5.10.0",
        "architecture": "amd64"
      }
    },
    "language": {
      "name": "java",
      "version": "11.0.18"
    },
    "request": {
      "timestamp": "2025-12-16 10:30:45",
      "ip": "203.0.113.45",
      "url": "https://gateway.example.com/api/v1/users?page=1",
      "user_agent": "Mozilla/5.0...",
      "method": "POST",
      "headers": {
        "Content-Type": "application/json",
        "Authorization": "********************"
      },
      "body": {
        "name": "John Doe",
        "password": "************",
        "page": "1"
      },
      "route_path": null,
      "query": {
        "page": "1"
      }
    },
    "response": {
      "code": 201,
      "size": 1024,
      "load_time": 245.5,
      "headers": {
        "Content-Type": "application/json"
      },
      "body": {
        "success": true,
        "user_id": 123
      }
    },
    "errors": []
  }
}
```

### Key Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `request.timestamp` | string | UTC timestamp in format `YYYY-MM-DD HH:mm:ss` |
| `request.ip` | string | Client IPv4 address (defaults to `bogon` if unavailable) |
| `request.method` | string | HTTP method (GET, POST, PUT, DELETE, PATCH, OPTIONS, etc.) |
| `request.body` | object | Request body merged with query parameters |
| `request.query` | object | Query parameters (kept separate) |
| `request.route_path` | string/null | API route pattern (e.g., `/api/{id}`) |
| `response.load_time` | float | Response time in milliseconds |
| `response.size` | integer | Response body size in bytes |
| `response.code` | integer | HTTP status code |
| `errors` | array | Array of error objects (empty if no errors) |

## Query Parameter Handling

Query parameters are handled in two ways:

1. **Merged into request body**: Query params are added to the request body
2. **Kept separate**: Original query params preserved in the `query` field

**Example:**

Request: `POST /api/users?source=web&ref=signup`
Body: `{"name": "John", "email": "john@example.com"}`

```json
{
  "request": {
    "body": {
      "name": "John",
      "email": "john@example.com",
      "source": "web",
      "ref": "signup"
    },
    "query": {
      "source": "web",
      "ref": "signup"
    }
  }
}
```

**Note:** If a key exists in both body and query, the body value takes precedence.

## Error Handling

The SDK is designed to **never** interfere with user API requests. All SDK operations are wrapped in try-catch blocks.

### Error Isolation

- SDK errors are logged but never thrown to end users
- Failed events are queued and retried automatically
- After retry attempts are exhausted, events are dropped (logged)
- User API traffic is never affected by SDK issues

### Exception Types

```java
TreblleException.missingApiKey()    // Thrown when API key is not configured
TreblleException.missingSdkToken()  // Thrown when SDK token is not configured
```

### Debug Mode

For development purposes, you can enable debug logging:

```properties
logger.treblle_publisher.level = DEBUG
```

This will log:
- Payload contents before sending
- Masking operations
- Retry attempts
- Detailed error traces

## Performance

The SDK is designed for minimal performance impact:

- **Asynchronous Processing**: All Treblle operations happen in background threads
- **Non-Blocking**: Request/response capture is lightweight and fast
- **Queue-Based**: Events are queued and processed separately from API traffic
- **Configurable Workers**: Adjust `TREBLLE_WORKER_THREADS` based on your load
- **Efficient Masking**: Field masking uses optimized recursive algorithms

## Ingestion Endpoints

The SDK automatically load-balances requests across three Treblle ingestion endpoints:

- `https://rocknrolla.treblle.com`
- `https://punisher.treblle.com`
- `https://sicario.treblle.com`

Each request randomly selects one of these endpoints for optimal distribution.

## Multi-Tenant Support

Enable Treblle for specific tenants using `TREBLLE_ENABLED_TENANT_DOMAINS`:

```bash
export TREBLLE_ENABLED_TENANT_DOMAINS="carbon.super,tenant1.com,tenant2.com"
```

Only APIs from these tenant domains will be monitored by Treblle.

## Troubleshooting

### Issue: No data appearing in Treblle dashboard

**Solutions:**
1. Verify environment variables are set correctly:
   ```bash
   echo $TREBLLE_SDK_TOKEN
   echo $TREBLLE_API_KEY
   ```
2. Check that your tenant domain is in `TREBLLE_ENABLED_TENANT_DOMAINS`
3. Review logs for errors:
   ```bash
   tail -f <WSO2_HOME>/repository/logs/wso2carbon.log | grep treblle
   ```
4. Verify the JAR is in the correct location:
   ```bash
   ls <WSO2_HOME>/repository/components/lib/treblle-*
   ```

### Issue: SDK errors affecting API requests

**Solutions:**
1. This should never happen - the SDK has comprehensive error handling
2. If you encounter this, please file an issue with:
   - Error logs
   - SDK version
   - WSO2 version
   - Request details

### Issue: Sensitive data not being masked

**Solutions:**
1. Check field names match exactly (case-insensitive)
2. Add custom fields via `ADDITIONAL_MASK_KEYWORDS`
3. Verify field masker is initialized:
   ```bash
   grep "Masking keywords" <WSO2_HOME>/repository/logs/wso2carbon.log
   ```

### Issue: High memory usage

**Solutions:**
1. Reduce `TREBLLE_QUEUE_SIZE`:
   ```bash
   export TREBLLE_QUEUE_SIZE=5000
   ```
2. Increase `TREBLLE_WORKER_THREADS` to process queue faster:
   ```bash
   export TREBLLE_WORKER_THREADS=2
   ```

## Examples

### Complete Configuration Example

```bash
# Required
export TREBLLE_SDK_TOKEN="your_sdk_token_from_treblle_com"
export TREBLLE_API_KEY="your_api_key_from_treblle_com"
export TREBLLE_GATEWAY_URL="https://api.example.com"

# Optional
export TREBLLE_ENABLED_TENANT_DOMAINS="carbon.super,tenant1.com"
export TREBLLE_QUEUE_SIZE=10000
export TREBLLE_WORKER_THREADS=2
export ADDITIONAL_MASK_KEYWORDS="custom_token,secret_field,private_key"
```

### Sample API Call with Masking

**Request:**
```bash
curl -X POST https://api.example.com/users \
  -H "Authorization: Bearer my-secret-token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "password": "SuperSecret123",
    "api_key": "user-api-key-abc"
  }'
```

**Captured by Treblle (masked):**
```json
{
  "request": {
    "headers": {
      "Authorization": "***********************",
      "Content-Type": "application/json"
    },
    "body": {
      "name": "John Doe",
      "email": "john@example.com",
      "password": "**************",
      "api_key": "*****************"
    }
  }
}
```

## Getting Started with Treblle

1. **Sign up**: Visit [treblle.com](https://treblle.com) and create a free account
2. **Create a Project**: Get your SDK token and API key
3. **Install SDK**: Follow the installation steps above
4. **Configure**: Set your environment variables
5. **Deploy**: Restart WSO2 and start monitoring your APIs!

## Support

- **Documentation**: [docs.treblle.com](https://docs.treblle.com)
- **GitHub Issues**: [github.com/Treblle/treblle-wso2](https://github.com/Treblle/treblle-wso2)
- **Email**: support@treblle.com
- **Slack**: Join our [Slack community](https://treblle.com/slack)

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

## License

This SDK is licensed under the MIT License. See [LICENSE](LICENSE) file for details.

## Version History

### v20 (Current)
- ✅ Full WSO2 4.3.0 support
- ✅ Recursive field masking
- ✅ Query parameter merging
- ✅ Base64 image detection
- ✅ IPv4 extraction
- ✅ Comprehensive error handling
- ✅ Multi-tenant support
- ✅ Asynchronous processing
- ✅ Automatic retry mechanism

---

Made with ❤️ by [Treblle](https://treblle.com)
