package bguspl.set.ex;

import java.util.Vector;

// Implements a simple BlockingQueue<T> with wait and notify, blocking threads that requested objects till
// an object is available.
public class WaitNotifyBlockingQueue<T> implements BlockingQueue<T> {

    private final Vector<T> vec;
    private final int maxSize;

    public WaitNotifyBlockingQueue(int maxSize) {
        this.maxSize = maxSize;
        vec = new Vector<>();
    }

    @Override
    public synchronized boolean add(T item) {
        while (vec.size() >= maxSize) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
            }
        }

        vec.add(item);
        this.notifyAll();

        return true;
    }

    @Override
    public synchronized T pop() throws InterruptedException {
        while (vec.isEmpty()) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
                throw new InterruptedException();
            }
        }

        T t = vec.remove(0);
        this.notifyAll();
        return t;
    }

    @Override
    public synchronized void clear() {
        while (!vec.isEmpty()) {
            vec.clear();
            this.notifyAll();
        }
    }
}
