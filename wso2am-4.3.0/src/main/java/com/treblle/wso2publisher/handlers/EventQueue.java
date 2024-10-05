package com.treblle.wso2publisher.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.treblle.wso2publisher.dto.TrebllePayload;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded concurrent queue wrapping for TrebllePayload events.
 * {@link java.util.concurrent.ArrayBlockingQueue}.
 */
public class EventQueue {
    private static final Log log = LogFactory.getLog(EventQueue.class);
    private final BlockingQueue<TrebllePayload> eventQueue;
    private final ExecutorService publisherExecutorService;
    private final AtomicInteger failureCount;

    /**
     * Constructor to initialize the EventQueue with the specified queue size and worker thread count.
     *
     * @param queueSize         the size of the event queue
     * @param workerThreadCount the number of worker threads
     */
    public EventQueue(int queueSize, int workerThreadCount) {

        // Initialize the executor service with a fixed number of worker threads
        publisherExecutorService = Executors.newFixedThreadPool(workerThreadCount,
                new DefaultAnalyticsThreadFactory("Queue-Worker"));

        // Retrieve the API key and project ID from environment variables
        String apiKey = System.getenv("TREBLLE_API_KEY");

        if (apiKey == null) {
            log.error("Treblle API Key is not set. Please set them in the environment variables.");
        }

        // Create a new PublisherClient with the retrieved API key and project ID
        PublisherClient publisherClient = new PublisherClient(apiKey );

        // Initialize the event queue with the specified size
        eventQueue = new LinkedBlockingQueue<>(queueSize);

        // Initialize the failure count to zero
        failureCount = new AtomicInteger(0);

        // Submit worker threads to the executor service
        for (int i = 0; i < workerThreadCount; i++) {
            publisherExecutorService.submit(new ParallelQueueWorker(eventQueue, publisherClient));
        }
    }


    /**
     * Method to add a payload to the event queue.
     *
     * @param payload the TrebllePayload object to be added to the queue
     */
    public void put(TrebllePayload payload) {

        // Check if the payload is null
        if (payload == null) {
            log.error("Payload is null. Skipping the event.");
            return;
        }

        try {
            // Attempt to add the payload to the queue
            if (!eventQueue.offer(payload)) {
                // Increment the failure count if the queue is full
                int count = failureCount.incrementAndGet();
                if (count == 1) {
                    log.error("Event queue is full. Starting to drop events.");
                } else if (count % 1000 == 0) {
                    log.error("Event queue is full. Events dropped so far - " + count);
                }
            }
        } catch (RejectedExecutionException e) {
            // Handle the RejectedExecutionException and log a warning
            log.warn("Task submission failed. Task queue might be full", e);
        }
    }

    /**
     * Method to finalize the EventQueue and shut down the executor service.
     */
    @Override
    protected void finalize() throws Throwable {
        // Shut down the executor service
        publisherExecutorService.shutdown();
        super.finalize();
    }
}
