package bguspl.set.ex;

import java.util.Vector;

// Implements a simple BlockingQueue<T> with wait and notify, blocking threads that requested objects till
// an object is available.
public class WaitNotifyBlockingQueue<T> implements BlockingQueue<T> {

	private Vector<T> itemvec;
	private int maxSize;
	
	public WaitNotifyBlockingQueue(int maxSize)
	{
		this.maxSize = maxSize;
		itemvec = new Vector<T>();
	}
	
	@Override
	public synchronized boolean add(T item) {
		while (itemvec.size() >= maxSize) {
			try {
				this.wait();
			} catch (InterruptedException ignored) {}
		}
		
		itemvec.add(item);
		this.notifyAll();
		
		return true;
	}

	@Override
	public synchronized T pop() {
		while (itemvec.size() <= 0) {
			try {
				this.wait();
			} catch (InterruptedException ignored) {}
		}
		
		T t = itemvec.remove(0);
		this.notifyAll();
		return t;
	}

	@Override
	public void clear() {
		
		while (itemvec.size() > 0) {
			itemvec.clear();
			this.notifyAll();
		}
		
		
	}

	
}
