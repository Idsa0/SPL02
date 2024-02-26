package bguspl.set.ex;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /*
     * used to show correct time in the freeze timers
     */
    private static final long FREEZE_ADJUST = 997;

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

    private final BlockingQueue<Integer> inputBuffer;

    private Dealer dealer;

    /*
     * some lock logic to let Dealer wait till initialization is done
     */
    private final Lock initalizationLock = new Lock();

    private volatile boolean initializationDoneFlag = false;

    /*
     * a lock and some flags to deal with the synchronization logic for declaring sets.
     */
    public final Lock myLock = new Lock();

    private volatile boolean waitingForDeclareResult = false;

    private volatile boolean declareResultIsPenalty = false;

    private volatile boolean declareResultIsPoint = false;
    private volatile boolean aiStartedFlag = false;

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
            if (!human)
                createArtificialIntelligence();
            initializationDoneFlag = true;
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
                synchronized (myLock) {
                    try {
                        waitingForDeclareResult = true;
                        myLock.notifyAll();
                        myLock.wait();

                        waitingForDeclareResult = false;

                        if (declareResultIsPenalty)
                            freeze(env.config.penaltyFreezeMillis);
                        else if (declareResultIsPoint)
                            freeze(env.config.pointFreezeMillis);

                        declareResultIsPenalty = false;
                        declareResultIsPoint = false;

                    } catch (InterruptedException ignored) {
                    }
                }
                inputBuffer.clear();
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    public void waitForInitializationComplete() {
        synchronized (initalizationLock) {
            while (!initializationDoneFlag || (!human && !aiStartedFlag)) {
                try {
                    initalizationLock.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void freeze(long freezeTime) throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        while (System.currentTimeMillis() < currentTime + freezeTime) {
            env.ui.setFreeze(this.id, currentTime + freezeTime - System.currentTimeMillis() + FREEZE_ADJUST);
            Thread.sleep(PLAYER_TIMER_REFRESH_RATE);
        }
        env.ui.setFreeze(id, 0);
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {

            synchronized (initalizationLock) {
                env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
                aiStartedFlag = true;
                initalizationLock.notifyAll();
            }
            while (!terminate) {
                inputBuffer.add((int) (Math.random() * env.config.tableSize));
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
        synchronized (myLock) {
            while (!waitingForDeclareResult) {
                try {
                    myLock.wait();
                } catch (InterruptedException ignored) {
                }
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
        synchronized (myLock) {
            while (!waitingForDeclareResult) {
                try {
                    myLock.wait();
                } catch (InterruptedException ignored) {
                }
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
            playerThread.join();
        } catch (InterruptedException ignored) {
        }
    }
}
