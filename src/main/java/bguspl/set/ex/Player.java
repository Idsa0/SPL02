package bguspl.set.ex;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    private static final long AI_DELAY_MILLIS = 1;
    
    private final long PLAYER_TIMER_REFRESH_RATE = 500;
    
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private BlockingQueue<Integer> inputBuffer;

    private Dealer dealer;

    public final Lock myLock = new Lock();
    
    private final Lock initalizationLock = new Lock();
    
    private volatile boolean initalizationDoneFlag = false;

    private volatile boolean iAmWaitingForDeclareResult = false;

    private volatile boolean declareResultIsPenalty = false;

    private volatile boolean declareResultIsPoint = false;
    
   
    
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        inputBuffer = new WaitNotifyBlockingQueue<>(env.config.featureSize);

    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");  
        synchronized (initalizationLock) {
	        if (!human) createArtificialIntelligence();
	        initalizationDoneFlag = true;
	        initalizationLock.notifyAll();
        }

        while (!terminate) {

            // read action from queue * thread will wait here for input.
            int keyPress;
        	try {
            	keyPress = inputBuffer.pop();
            } catch (InterruptedException e) {
            	continue;
            }

            boolean tokensChanged = table.token(id, keyPress);

            // if 3 tokens, notify dealer, wait till dealer finishes.
            if (table.tokenAmount(id) == env.config.featureSize && tokensChanged) {
                dealer.declareSet(id);
                synchronized (myLock){
                	try {
                		iAmWaitingForDeclareResult = true;
                    	myLock.notifyAll();
                    	myLock.wait();

                    	iAmWaitingForDeclareResult = false;
                    	
	                	if (declareResultIsPenalty) {
	                		freeze(env.config.penaltyFreezeMillis);
	                	}
	                	if (declareResultIsPoint) {
	                		freeze(env.config.pointFreezeMillis);
	                	}
	                	declareResultIsPenalty = false;
	                	declareResultIsPoint = false;

                	} catch (InterruptedException e) {}
                }
                inputBuffer.clear();


            }
            // TODO implement main player loop
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    
    public void waitForInitializationComplete() {
    	synchronized(initalizationLock) {
    		if (!initalizationDoneFlag) {
    			try {
    				initalizationLock.wait();
    			} catch (InterruptedException ignored) {}
    		}
    	}
    }
    
	private void freeze(long freezeTime) throws InterruptedException {
		long currentTime = System.currentTimeMillis();
		while (System.currentTimeMillis() < currentTime + freezeTime) {
			env.ui.setFreeze(this.id, currentTime + freezeTime - System.currentTimeMillis());
			Thread.sleep(PLAYER_TIMER_REFRESH_RATE);
		}
		env.ui.setFreeze(id, -1);
	}

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator

                inputBuffer.add((int) (Math.random() * env.config.tableSize));
                try {
                    synchronized (this) {
                        wait(AI_DELAY_MILLIS);
                    }
                } catch (InterruptedException ignored) {
                }

//            	try {
//                    synchronized (this) { wait(); }
//                } catch (InterruptedException ignored) {}
//                TODO they added this
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        playerThread.interrupt();
        if (!human)
        	aiThread.interrupt();

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
    	if (human)
    		inputBuffer.add(slot);
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
    	synchronized(myLock) {
    		while (!iAmWaitingForDeclareResult) {
	        	try {
					myLock.wait();
	        	} catch (InterruptedException ignored) {}
    		}
    		declareResultIsPoint = true;
    		myLock.notifyAll();
        }

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        synchronized(myLock) {
    		while (!iAmWaitingForDeclareResult) {
	        	try {
					myLock.wait();
	        	} catch (InterruptedException ignored) {}
    		}
    		declareResultIsPenalty = true;
    		myLock.notifyAll();
        }
    }

    public int score() {
        return score;
    }
    
    public void join() {
    	try {
    		playerThread.join();}
    	catch (InterruptedException ignored) {}
    }
}
