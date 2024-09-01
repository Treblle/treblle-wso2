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

    public EventQueue(int queueSize, int workerThreadCount) {
        publisherExecutorService = Executors.newFixedThreadPool(workerThreadCount,
                new DefaultAnalyticsThreadFactory("Queue-Worker"));
        String apiKey = System.getenv("TREBLLE_API_KEY");
        String projectId = System.getenv("TREBLLE_PROJECT_ID");
        PublisherClient publisherClient = new PublisherClient(apiKey, projectId);
        eventQueue = new LinkedBlockingQueue<>(queueSize);
        failureCount = new AtomicInteger(0);
        for (int i = 0; i < workerThreadCount; i++) {
            publisherExecutorService.submit(new ParallelQueueWorker(eventQueue, publisherClient));
        }
    }

    public void put(TrebllePayload payload) {
        try {
            if (!eventQueue.offer(payload)) {
                int count = failureCount.incrementAndGet();
                if (count == 1) {
                    log.error("Event queue is full. Starting to drop events.");
                } else if (count % 1000 == 0) {
                    log.error("Event queue is full. Events dropped so far - " + count);
                }
            }
        } catch (RejectedExecutionException e) {
            log.warn("Task submission failed. Task queue might be full", e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        publisherExecutorService.shutdown();
        super.finalize();
    }
}
