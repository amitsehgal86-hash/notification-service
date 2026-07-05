package com.notification.infrastructure.queue;

import com.notification.config.NotificationProperties;
import com.notification.model.NotificationRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-JVM work queue that replaces SQS for this plug-and-play build. Bounded so a runaway producer
 * cannot exhaust the heap.
 */
@Component
public class NotificationQueue {

    private final BlockingQueue<NotificationRequest> queue;

    public NotificationQueue(NotificationProperties props) {
        this.queue = new ArrayBlockingQueue<>(props.getQueue().getCapacity());
    }

    /** Blocks if the queue is full (back-pressure), matching SQS-like durability semantics loosely. */
    public void enqueue(NotificationRequest request) throws InterruptedException {
        queue.put(request);
    }

    public NotificationRequest poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
