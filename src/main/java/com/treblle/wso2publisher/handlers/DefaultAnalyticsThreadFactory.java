
package com.treblle.wso2publisher.handlers;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom Thread Factory for publisher impl.
 */
public class DefaultAnalyticsThreadFactory implements ThreadFactory {
    private static final AtomicInteger poolNumber = new AtomicInteger(1);
    final ThreadGroup group;
    final AtomicInteger threadNumber = new AtomicInteger(1);
    final String namePrefix;

    public DefaultAnalyticsThreadFactory(String threadPoolExecutorName) {
        group = Thread.currentThread().getThreadGroup();
        namePrefix = "Treblle-Publisher-" + threadPoolExecutorName + "-pool-" + poolNumber.getAndIncrement() +
                "-thread-";
    }

    public Thread newThread(Runnable r) {
        Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
        // Daemon: these threads only drain a telemetry queue. Non-daemon threads would
        // keep running across bundle redeploys (nothing invokes DataHolder.shutdown())
        // and could delay JVM shutdown.
        t.setDaemon(true);
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        return t;
    }
}
