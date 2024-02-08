package bguspl.set.ex;


// queue with maximum amount of objects MAX and that works with concurrency.
public interface BlockingQueue<T> {
	
	public boolean add(T item);
	
	public T pop();

	public void clear();
}
