package com.trebelle.ws02publisher.handlers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.trebelle.ws02publisher.dto.TrebllePayload;
import java.util.concurrent.BlockingQueue;

/**
 * Will dequeue the events from queues and send then to the publisher client
 * {@link PublisherClient}.
 */
public class ParallelQueueWorker implements Runnable {
    private static final Log log = LogFactory.getLog(ParallelQueueWorker.class);
    private BlockingQueue<TrebllePayload> eventQueue;
    private PublisherClient client;

    public ParallelQueueWorker(BlockingQueue<TrebllePayload> queue, PublisherClient publisherClient) {
        this.eventQueue = queue;
        this.client = publisherClient;
    }

    public void run() {

        while (true) {
            TrebllePayload payload;
            try {
                payload = eventQueue.take();
                client.publish(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Treblle event sending failed. Event will be dropped", e);
            }
        }
    }

}
