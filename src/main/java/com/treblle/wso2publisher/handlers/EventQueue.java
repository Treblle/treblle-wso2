package com.treblle.wso2publisher.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.treblle.wso2publisher.dto.TrebllePayload;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded concurrent queue wrapping for TrebllePayload events.
 * Bounded both by entry count (queue size) and by an approximate total byte budget:
 * counting entries alone does not bound heap, since a single payload can carry up to
 * ~4MB of captured request/response body.
 */
public class EventQueue {
    private static final Log log = LogFactory.getLog(EventQueue.class);

    /** Approximate ceiling for bytes held in the queue at once (bodies dominate). */
    public static final long DEFAULT_MAX_QUEUE_BYTES = 256L * 1024 * 1024; // 256MB
    private static final String TREBLLE_QUEUE_MAX_BYTES = "TREBLLE_QUEUE_MAX_BYTES";

    /** Fixed per-payload overhead estimate (headers, metadata, DTO fields). */
    private static final long PAYLOAD_BASE_BYTES = 2048;

    private final BlockingQueue<TrebllePayload> eventQueue;
    private final ExecutorService publisherExecutorService;
    private final AtomicInteger failureCount;
    private final AtomicLong queuedBytes = new AtomicLong(0);
    private final long maxQueueBytes;

    /**
     * Constructor to initialize the EventQueue with the specified queue size, worker thread count, and HTTP client.
     *
     * @param queueSize         the size of the event queue
     * @param workerThreadCount the number of worker threads
     * @param httpClient        the pooled HTTP client for connection reuse
     */
    public EventQueue(int queueSize, int workerThreadCount, org.apache.http.impl.client.CloseableHttpClient httpClient) {

        // Initialize the executor service with a fixed number of worker threads
        publisherExecutorService = Executors.newFixedThreadPool(workerThreadCount,
                new DefaultAnalyticsThreadFactory("Queue-Worker"));

        // Retrieve the SDK token and API key from environment variables
        String sdkToken = System.getenv("TREBLLE_SDK_TOKEN");
        String apiKey = System.getenv("TREBLLE_API_KEY");

        if (sdkToken == null || apiKey == null) {
            log.error("[TREBLLE]: SDK Token or API Key is not set. Please set them in the environment variables.");
        }

        this.maxQueueBytes = resolveMaxQueueBytes();

        // Create a new PublisherClient with the retrieved SDK token, API key, and pooled HTTP client
        PublisherClient publisherClient = new PublisherClient(sdkToken, apiKey, httpClient);

        // Initialize the event queue with the specified size
        eventQueue = new LinkedBlockingQueue<>(queueSize);

        // Initialize the failure count to zero
        failureCount = new AtomicInteger(0);

        // Submit worker threads to the executor service
        for (int i = 0; i < workerThreadCount; i++) {
            publisherExecutorService.submit(new ParallelQueueWorker(this, publisherClient));
        }
    }

    private static long resolveMaxQueueBytes() {
        String configured = System.getenv(TREBLLE_QUEUE_MAX_BYTES);
        if (configured != null) {
            try {
                long value = Long.parseLong(configured.trim());
                if (value >= 1) {
                    return value;
                }
                log.warn("[TREBLLE]: TREBLLE_QUEUE_MAX_BYTES must be at least 1. Using default: "
                        + DEFAULT_MAX_QUEUE_BYTES);
            } catch (NumberFormatException e) {
                log.warn("[TREBLLE]: Invalid TREBLLE_QUEUE_MAX_BYTES value. Using default: "
                        + DEFAULT_MAX_QUEUE_BYTES, e);
            }
        }
        return DEFAULT_MAX_QUEUE_BYTES;
    }

    /**
     * Deterministic per-payload heap estimate — must return the same value at
     * enqueue and dequeue time so the byte accounting balances.
     */
    private static long estimatePayloadBytes(TrebllePayload payload) {
        long size = PAYLOAD_BASE_BYTES;
        if (payload.getData() != null) {
            if (payload.getData().getRequest() != null && payload.getData().getRequest().getBodyRaw() != null) {
                size += payload.getData().getRequest().getBodyRaw().length();
            }
            if (payload.getData().getResponse() != null && payload.getData().getResponse().getBodyRaw() != null) {
                size += payload.getData().getResponse().getBodyRaw().length();
            }
        }
        return size;
    }

    /**
     * Method to add a payload to the event queue.
     *
     * @param payload the TrebllePayload object to be added to the queue
     */
    public void put(TrebllePayload payload) {

        // Check if the payload is null
        if (payload == null) {
            log.error("[TREBLLE]: Payload is null. Skipping the event.");
            return;
        }

        long estimatedBytes = estimatePayloadBytes(payload);

        // Enforce the byte budget before the count-bounded offer: with large captured
        // bodies, entry count alone does not bound heap usage.
        if (queuedBytes.get() + estimatedBytes > maxQueueBytes) {
            countDrop("byte budget of " + maxQueueBytes + " bytes reached");
            return;
        }

        if (eventQueue.offer(payload)) {
            queuedBytes.addAndGet(estimatedBytes);
        } else {
            countDrop("queue is full");
        }
    }

    private void countDrop(String reason) {
        int count = failureCount.incrementAndGet();
        if (count == 1) {
            log.error("[TREBLLE]: Event dropped (" + reason + "). Starting to drop events.");
        } else if (count % 1000 == 0) {
            log.error("[TREBLLE]: Events dropped so far - " + count + " (last reason: " + reason + ")");
        }
    }

    /**
     * Blocking take used by worker threads; releases the payload's share of the
     * byte budget as it leaves the queue.
     */
    TrebllePayload take() throws InterruptedException {
        TrebllePayload payload = eventQueue.take();
        queuedBytes.addAndGet(-estimatePayloadBytes(payload));
        return payload;
    }

    /**
     * Method to shut down the EventQueue and executor service gracefully.
     * Should be called during application shutdown.
     */
    public void shutdown() {
        log.info("[TREBLLE]: Shutting down event queue and worker threads");
        // Shut down the executor service gracefully
        publisherExecutorService.shutdown();
        try {
            // Wait for tasks to complete, with a timeout
            if (!publisherExecutorService.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[TREBLLE]: Worker threads did not terminate in time, forcing shutdown");
                publisherExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[TREBLLE]: Interrupted while waiting for worker threads to terminate", e);
            publisherExecutorService.shutdownNow();
        }
    }
}
