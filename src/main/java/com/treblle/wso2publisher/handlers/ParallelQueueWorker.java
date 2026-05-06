package com.treblle.wso2publisher.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.treblle.wso2publisher.dto.TrebllePayload;

import java.util.concurrent.BlockingQueue;

/**
 * Will dequeue the events from queues and send then to the publisher client
 * {@link PublisherClient}.
 */
public class ParallelQueueWorker implements Runnable {
    private static final Log log = LogFactory.getLog(ParallelQueueWorker.class);
    // Queue to hold the events to be processed
    private BlockingQueue<TrebllePayload> eventQueue;
    private PublisherClient client;

    /**
     * Constructor to initialize the ParallelQueueWorker with the event queue and publisher client.
     *
     * @param queue           the queue holding the events
     * @param publisherClient the client to publish the events
     */
    public ParallelQueueWorker(BlockingQueue<TrebllePayload> queue, PublisherClient publisherClient) {
        this.eventQueue = queue;
        this.client = publisherClient;
    }

    /**
     * Method to run the worker thread which processes the events from the queue.
     * Exits gracefully when interrupted.
     */
    public void run() {

        while (!Thread.currentThread().isInterrupted()) {
            TrebllePayload payload;
            try {
                // Retrieve the payload from the queue
                payload = eventQueue.take();

                // Publish the payload using the client
                client.publish(payload);
            } catch (InterruptedException e) {
                // Handle the InterruptedException and interrupt the thread
                Thread.currentThread().interrupt();
                log.info("[TREBLLE]: Worker thread interrupted, shutting down gracefully");
                break; // Exit the loop to allow graceful shutdown
            } catch (Exception e) {
                // Handle general exceptions and log the error - never crash the worker thread
                log.error("[TREBLLE]: Event sending failed. Event will be dropped", e);
            }
        }

        log.info("[TREBLLE]: Worker thread shutdown complete");
    }

}
