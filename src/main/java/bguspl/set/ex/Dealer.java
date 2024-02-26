package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    private Thread dealerThread;
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    private final long RESHUFFLE_TIMER_REFRESH_RATE_SHORT = 33;

    // This is basically a second, but I wanted an excuse to use a prime number
    private final long RESHUFFLE_TIMER_REFRESH_RATE_LONG = 997;
    private long RESHUFFLE_TIMER_REFRESH_RATE;

    private final Lock declareSetLock;

    private final Queue<Integer> setContenders;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime;

    /**
     * Used for handling the different game modes depending on turnTimeoutMillis
     */

    private enum TurnTimeoutMode {
        NO_CLOCK, LAST_ACTION_CLOCK, NORMAL_CLOCK
    }

    private final TurnTimeoutMode turnTimeoutMode;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        terminate = false;

        declareSetLock = new Lock();
        setContenders = new PriorityQueue<>();

        if (env.config.turnTimeoutMillis < 0)
            turnTimeoutMode = TurnTimeoutMode.NO_CLOCK;
        else if (env.config.turnTimeoutMillis == 0)
            turnTimeoutMode = TurnTimeoutMode.LAST_ACTION_CLOCK;
        else
            turnTimeoutMode = TurnTimeoutMode.NORMAL_CLOCK;

        RESHUFFLE_TIMER_REFRESH_RATE = RESHUFFLE_TIMER_REFRESH_RATE_LONG;

        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        if (turnTimeoutMode == TurnTimeoutMode.NO_CLOCK)
            reshuffleTime = Long.MAX_VALUE;
        else if (turnTimeoutMode == TurnTimeoutMode.LAST_ACTION_CLOCK)
            reshuffleTime = System.currentTimeMillis();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        for (Player player : players) {
            new Thread(player, env.config.playerNames[player.id]).start();
            player.waitForInitializationComplete();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();

        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
            players[i].join();
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        if (turnTimeoutMode == TurnTimeoutMode.NO_CLOCK || turnTimeoutMode == TurnTimeoutMode.LAST_ACTION_CLOCK)
            while (!terminate && table.setOnTable()) {
                sleepUntilWokenOrTimeout();
                updateTimerDisplay(false);
                removeCardsFromTable();
                placeCardsOnTable();
            }
        else
            while (!terminate && System.currentTimeMillis() < reshuffleTime) {
                sleepUntilWokenOrTimeout();
                updateTimerDisplay(false);
                removeCardsFromTable();
                placeCardsOnTable();
            }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        dealerThread.interrupt();
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(table.getDeck(), 1).isEmpty();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (setContenders.isEmpty())
            return;

        synchronized (declareSetLock) {
            while (!setContenders.isEmpty()) {
                int player = setContenders.remove();
                Integer[] playerTokens = table.getPlayerTokens(player).toArray(new Integer[0]);
                int[] playerCards = new int[playerTokens.length];

                boolean isSet = true;
                for (int i = 0; i < playerTokens.length; i++) {
                    if (table.slotToCard[playerTokens[i]] != null)
                        playerCards[i] = table.slotToCard[playerTokens[i]];
                    else {
                        playerCards[i] = -1;
                        isSet = false;
                    }
                }

                if (playerCards.length != env.config.featureSize)
                    isSet = false;

                if (isSet)
                    isSet = env.util.testSet(playerCards);

                // Reaching this code isSet tells us whether the player has a legitimate set
                if (isSet) {
                    for (int slot : playerTokens) {
                        table.removeCard(slot);
                        deck.remove((Integer) slot); // updating dealer.deck in case testing is needed.
                        updateTimerDisplay(true);
                    }
                    players[player].point();
                } else {
                    players[player].penalty();
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        ArrayList<Integer> cardsToPlace = IntStream.range(0, env.config.tableSize).filter(i -> !table.hasCard(i)).boxed().collect(Collectors.toCollection(() -> new ArrayList<>(env.config.tableSize)));
        Collections.shuffle(cardsToPlace);

        int startingCards = table.countCards();
        for (Integer i : cardsToPlace)
            if (!table.deckEmpty())
                synchronized (table) {
                    table.placeCardFromDeck(i);
                }

        if (table.countCards() > startingCards && env.config.hints)
            table.hints();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (declareSetLock) {
            if (setContenders.isEmpty()) {
                try {
                    if (turnTimeoutMode == TurnTimeoutMode.NO_CLOCK)
                        declareSetLock.wait();
                    else
                        declareSetLock.wait(RESHUFFLE_TIMER_REFRESH_RATE);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (turnTimeoutMode == TurnTimeoutMode.NO_CLOCK)
            return;

        if (turnTimeoutMode == TurnTimeoutMode.LAST_ACTION_CLOCK) {
            if (reset)
                reshuffleTime = System.currentTimeMillis();

            env.ui.setElapsed(System.currentTimeMillis() - reshuffleTime);
            return;
        }

        if (turnTimeoutMode == TurnTimeoutMode.NORMAL_CLOCK) {
            if (reset)
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;

            long timeLeft = reshuffleTime - System.currentTimeMillis();
            env.ui.setCountdown(timeLeft, timeLeft <= env.config.turnTimeoutWarningMillis);

            RESHUFFLE_TIMER_REFRESH_RATE = timeLeft <= env.config.turnTimeoutWarningMillis ?
                    RESHUFFLE_TIMER_REFRESH_RATE_SHORT : RESHUFFLE_TIMER_REFRESH_RATE_LONG;
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        ArrayList<Integer> list = IntStream.range(0, env.config.tableSize).boxed().collect(Collectors.toCollection(() -> new ArrayList<>(env.config.tableSize)));
        Collections.shuffle(list);

        for (Integer i : list)
            table.removeCardAndReturnToDeck(i);

        table.shuffle();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        if (players.length == 0)
            return;

        int topScore = players[0].score();
        List<Integer> winners = new ArrayList<>();
        for (Player p : players)
            if (p.score() > topScore)
                topScore = p.score();

        for (int i = 0; i < players.length; i++)
            if (players[i].score() == topScore)
                winners.add(i);

        int[] winnersArray = new int[winners.size()];
        for (int i = 0; i < winnersArray.length; i++)
            winnersArray[i] = winners.get(i);
        env.ui.announceWinner(winnersArray);
    }

    public void declareSet(int id) {
        synchronized (declareSetLock) {
            setContenders.add(id);
            declareSetLock.notifyAll();
        }
    }
}
