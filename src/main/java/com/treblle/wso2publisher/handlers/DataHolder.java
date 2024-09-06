package com.treblle.wso2publisher.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class DataHolder {

    private static final Log log = LogFactory.getLog(DataHolder.class);
    private static final DataHolder instance = new DataHolder();
    private EventQueue eventQueue;
    public static final int DEFAULT_QUEUE_SIZE = 20000;
    public static final int DEFAULT_WORKER_THREADS = 1;
    public String gatewayURL;

    private DataHolder() {

        int queueSize = DEFAULT_QUEUE_SIZE;
        int workerThreads = DEFAULT_WORKER_THREADS;
        gatewayURL = "https://test.com";

        // Retrieve the queue size from environment variables
        if (System.getenv("TREBLLE_QUEUE_SIZE") != null) {
            queueSize = Integer.parseInt(System.getenv("TREBLLE_QUEUE_SIZE"));
        }

        // Retrieve the number of worker threads from environment variables
        if (System.getenv("TREBLLE_WORKER_THREADS") != null) {
            workerThreads = Integer.parseInt(System.getenv("TREBLLE_WORKER_THREADS"));
        }

        // Retrieve the gateway URL from environment variables
        if (System.getenv("TREBLLE_GATEWAY_URL") != null) {
            gatewayURL = System.getenv("TREBLLE_GATEWAY_URL");
        }

        // Initialize the event queue with the specified size and worker threads
        eventQueue = new EventQueue(queueSize, workerThreads);
        log.debug("DataHolder initialized with queue size: " + queueSize + " and worker threads: " + workerThreads);
    }

    public static DataHolder getInstance() {
        return instance;
    }

    public EventQueue getEventQueue() {
        return eventQueue;
    }

    public String getGatewayURL() {
        return gatewayURL;
    }
}
