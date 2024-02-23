package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

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

    private final long RESHUFFLE_TIMER_REFRESH_RATE = 33;

    private final Lock declareSetLock;

    private Queue<Integer> setContenders;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        terminate = false;

        declareSetLock = new Lock();
        setContenders = new PriorityQueue<>();

        // TODO: initialize some values according to env.config
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis; // TODO: timer bonus.
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
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
        for (int i = players.length - 1 ; i >= 0; i--) {
        	players[i].terminate();
        	players[i].join();
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            removeCardsFromTable();
            placeCardsOnTable();
            updateTimerDisplay(false);
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        // TODO implement
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
                        isSet=false;
                    }
                }
                if (playerCards.length != env.config.featureSize)
                	isSet = false;
                
                if (isSet)
                	isSet = env.util.testSet(playerCards);

                if (isSet) {
                    players[player].point();
                    for (int slot : playerTokens) {
                        table.removeCard(slot);
                        deck.remove((Integer) slot); // updating dealer.deck in case testing is needed.
                        updateTimerDisplay(true);
                    }
                } else {
                    players[player].penalty();
                }
            }
        }
        
        
        // TODO implement
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        ArrayList<Integer> cardsToPlace = IntStream.range(0, env.config.tableSize).filter(i -> !table.hasCard(i)).boxed().collect(Collectors.toCollection(() -> new ArrayList<>(env.config.tableSize)));
        Collections.shuffle(cardsToPlace);

        for (Integer i : cardsToPlace)
            if (!table.deckEmpty())
                table.placeCardFromDeck(i);
        // TODO implement
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if (!setContenders.isEmpty()) {
            try { // TODO: timer bonus.
                Thread.sleep(RESHUFFLE_TIMER_REFRESH_RATE);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset)
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis - 30000; // TODO remove this before submitting

        long timeLeft = reshuffleTime - System.currentTimeMillis();
        env.ui.setCountdown(timeLeft, timeLeft <= env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        ArrayList<Integer> list = IntStream.range(0, env.config.tableSize).boxed().collect(Collectors.toCollection(() -> new ArrayList<>(env.config.tableSize)));
        Collections.shuffle(list);

        for (Integer i : list)
            table.removeCardAndReturnToDeck(i);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int topScore = players[0].score(); // TODO could this array be empty?
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
        // TODO implement
    }

    public void declareSet(int id) {
        synchronized (declareSetLock) {
            setContenders.add(id);
            declareSetLock.notifyAll();
        }
    }
}
