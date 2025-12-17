package com.treblle.wso2publisher.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class DataHolder {

    private static final Log log = LogFactory.getLog(DataHolder.class);
    private static final DataHolder instance = new DataHolder();
    private EventQueue eventQueue;
    private CloseableHttpClient httpClient;
    private PoolingHttpClientConnectionManager connectionManager;
    public static final int DEFAULT_QUEUE_SIZE = 20000;
    public static final int DEFAULT_WORKER_THREADS = 1;
    public String gatewayURL;
    Map<String, String> enabledTenantDomains = new HashMap<>();
    private static final String TENANT_DOMAINS = "TREBLLE_ENABLED_TENANT_DOMAINS";
    private static final String TREBLLE_QUEUE_SIZE = "TREBLLE_QUEUE_SIZE";
    private static final String TREBLLE_WORKER_THREADS = "TREBLLE_WORKER_THREADS";
    private static final String TREBLLE_GATEWAY_URL = "TREBLLE_GATEWAY_URL";

    private DataHolder() {

        int queueSize = DEFAULT_QUEUE_SIZE;
        int workerThreads = DEFAULT_WORKER_THREADS;
        gatewayURL = "https://test.com";

        // Retrieve the queue size from environment variables
        if (System.getenv(TREBLLE_QUEUE_SIZE) != null) {
            try {
                queueSize = Integer.parseInt(System.getenv(TREBLLE_QUEUE_SIZE));
            } catch (NumberFormatException e) {
                log.warn("Invalid TREBLLE_QUEUE_SIZE value. Using default: " + DEFAULT_QUEUE_SIZE, e);
                queueSize = DEFAULT_QUEUE_SIZE;
            }
        }

        // Retrieve the number of worker threads from environment variables
        if (System.getenv(TREBLLE_WORKER_THREADS) != null) {
            try {
                workerThreads = Integer.parseInt(System.getenv(TREBLLE_WORKER_THREADS));
                if (workerThreads < 1) {
                    log.warn("TREBLLE_WORKER_THREADS must be at least 1. Using default: " + DEFAULT_WORKER_THREADS);
                    workerThreads = DEFAULT_WORKER_THREADS;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid TREBLLE_WORKER_THREADS value. Using default: " + DEFAULT_WORKER_THREADS, e);
                workerThreads = DEFAULT_WORKER_THREADS;
            }
        }

        // Retrieve the gateway URL from environment variables
        if (System.getenv(TREBLLE_GATEWAY_URL) != null) {
            gatewayURL = System.getenv(TREBLLE_GATEWAY_URL);
        }

        String tenantDomains = System.getProperty(TENANT_DOMAINS, System.getenv(TENANT_DOMAINS));
        if (tenantDomains != null) {
            String[] tenantDomainArray = tenantDomains.split(",");

            for (String tenantDomain : tenantDomainArray) {
                enabledTenantDomains.put(tenantDomain, tenantDomain);
            }
        }

        // Initialize pooled HTTP client for efficient connection reuse
        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100);
        connectionManager.setDefaultMaxPerRoute(20);
        connectionManager.setValidateAfterInactivity(2000);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(10000)
                .setConnectionRequestTimeout(5000)
                .build();

        httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManagerShared(false)
                .build();

        log.debug("Initialized pooled HTTP client with max connections: 100, per route: 20");

        // Initialize the event queue with the specified size, worker threads, and HTTP client
        eventQueue = new EventQueue(queueSize, workerThreads, httpClient);
        log.debug("DataHolder initialized with queue size: " + queueSize + " and worker threads: " + workerThreads);
        log.debug("Enabled Tenant Domains: " + Arrays.toString(enabledTenantDomains.keySet().toArray()));
    }

    public static DataHolder getInstance() {
        return instance;
    }

    public EventQueue getEventQueue() {
        return eventQueue;
    }

    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    public String getGatewayURL() {
        return gatewayURL;
    }

    public Map<String, String> getEnabledTenantDomains() {
        return enabledTenantDomains;
    }

    /**
     * Shutdown method to gracefully close resources.
     * Should be called during application shutdown.
     */
    public void shutdown() {
        log.info("Shutting down Treblle DataHolder resources");

        // Shutdown event queue and worker threads
        if (eventQueue != null) {
            eventQueue.shutdown();
        }

        // Close HTTP client and connection manager
        if (httpClient != null) {
            try {
                httpClient.close();
                log.debug("HTTP client closed successfully");
            } catch (IOException e) {
                log.error("Error closing HTTP client", e);
            }
        }

        if (connectionManager != null) {
            connectionManager.close();
            log.debug("Connection manager closed successfully");
        }
    }
}
