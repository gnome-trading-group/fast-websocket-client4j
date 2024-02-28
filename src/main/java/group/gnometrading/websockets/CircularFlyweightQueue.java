package group.gnometrading.websockets;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A SPSC circular flyweight queue. Do not use multiple producers or multiple consumers, you will regret it.
 * @param <E> the item in the queue
 */
class CircularFlyweightQueue<E> {

    private int currentSize;
    private final E[] circularQueueElements;

    private int rear;
    private int front;

    /**
     * Create a new circular flyweight queue.
     * @param maxSize maximum size of the queue
     * @param factory the flyweight creator. Will be called `maxSize` times on init.
     */
    public CircularFlyweightQueue(int maxSize, Supplier<E> factory) {
        circularQueueElements = (E[]) new Object[maxSize];
        for (int i = 0; i < maxSize; i++) {
            circularQueueElements[i] = factory.get();
        }
        currentSize = 0;
        front = -1;
        rear = -1;
    }

    public void clear() {
        front = -1;
        rear = -1;
        currentSize = 0;
    }

    public void enqueue(Consumer<E> consumer) {
        if (isFull()) {
            throw new IllegalStateException("Circular queue is full. Element cannot be added");
        } else {
            rear = (rear + 1) % circularQueueElements.length;
            consumer.accept(circularQueueElements[rear]);

            if (front == -1) {
                front = rear;
            }
            currentSize++;
        }
    }

    public void pop(Consumer<E> consumer) {
        if (isEmpty()) {
            throw new IllegalStateException("Circular queue is empty. Element cannot be retrieved");
        } else {
            consumer.accept(circularQueueElements[front]);
            front = (front + 1) % circularQueueElements.length;
            currentSize--;
        }
    }

    public boolean isFull() {
        return currentSize == circularQueueElements.length;
    }

    public boolean isEmpty() {
        return currentSize == 0;
    }

}