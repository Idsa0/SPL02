package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * Holds players tokens
     */
    protected final Vector<Integer>[] playerTokens;

    private final List<Integer> deck;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;

        // Java does not allow an array of vectors to be created. Must be cast.
        //noinspection unchecked
        this.playerTokens = (Vector<Integer>[]) new Vector[env.config.players];

        for (int i = 0; i < env.config.players; i++)
            playerTokens[i] = new Vector<>();

        deck = new ArrayList<>(env.config.deckSize);
        for (int i = 0; i < env.config.deckSize; i++)
            deck.add(i);

        // in case slotToCard is not empty:
        for (Integer i : slotToCard)
            deck.remove(i);

        Collections.shuffle(deck);
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public synchronized void hints() {
        List<Integer> deck0 = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck0, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    public synchronized boolean setOnTable() {
        List<Integer> deck0 = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        return !env.util.findSets(deck0, Integer.MAX_VALUE).isEmpty();
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public synchronized int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    public synchronized boolean hasCard(int slot) {
        return slotToCard[slot] != null;
    }

    /**
     * Places a card on the table in a grid slot.
     *
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     * @post - the card placed is on the table, in the assigned slot.
     */
    public synchronized void placeCard(int card, int slot) {
        if (!deck.contains(card))
            throw new RuntimeException("Table::placeCard called with a card not in the deck!");
        if (!legalSlot(slot))
            throw new RuntimeException("Table::placeCard called with a non-existing slot!");
        if (slotToCard[slot] != null) {
            throw new RuntimeException("Table::placeCard placing card where there is another card");
        }
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        deck.remove((Integer) card);
        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        env.ui.placeCard(card, slot);
    }

    public synchronized void placeCardFromDeck(int slot) {
        placeCard(deck.get(0), slot);
    }

    public synchronized void removeCardAndReturnToDeck(int slot) {
        Integer card = removeCardWorker(slot);
        if (card != null)
            deck.add(card);
    }

    private synchronized Integer removeCardWorker(int slot) {
        if (!legalSlot(slot))
            throw new RuntimeException();
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        for (int i = 0; i < env.config.players; i++)
            removeToken(i, slot);

        Integer card = slotToCard[slot];
        if (card != null)
            cardToSlot[card] = null;
        slotToCard[slot] = null;

        env.ui.removeCard(slot);
        return card;
    }

    /**
     * Removes a card from a grid slot on the table.
     *
     * @param slot - the slot from which to remove the card.
     */
    public synchronized void removeCard(int slot) {
        removeCardWorker(slot);
    }

    public synchronized boolean token(int player, int slot) {
        if (!tokenLegalSlot(slot))
            return false;

        if (playerTokens[player].contains(slot))
            return removeToken(player, slot);
        else {
            int tokens = tokenAmount(player);
            placeToken(player, slot);
            return tokenAmount(player) > tokens;
        }
    }

    /**
     * Places a player token on a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public synchronized void placeToken(int player, int slot) {
        if (!tokenLegalSlot(slot))
            return;

        if (tokenAmount(player) >= env.config.featureSize)
            return;

        if (playerTokens[player].contains(slot))
            return;

        playerTokens[player].add(slot);
        env.ui.placeToken(player, slot);
    }

    // a tokenLegalSlot is a legalSlot that has a card.
    private synchronized boolean tokenLegalSlot(int slot) {
        return legalSlot(slot) && slotToCard[slot] != null;
    }

    private synchronized boolean legalSlot(int slot) {
        return slot < slotToCard.length;
    }

    /**
     * Removes a token of a player from a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public synchronized boolean removeToken(int player, int slot) {
        if (!tokenLegalSlot(slot))
            return false;

        if (tokenAmount(player) <= 0)
            return false;

        if (playerTokens[player].contains(slot)) {
            playerTokens[player].remove((Integer) slot);
            env.ui.removeToken(player, slot);
            return true;
        }

        return false;
    }

    public synchronized int tokenAmount(int player) {
        return playerTokens[player].size();

    }

    public synchronized boolean deckEmpty() {
        return deck.isEmpty();
    }

    public synchronized Vector<Integer> getPlayerTokens(int player) {
        return playerTokens[player];
    }

    public synchronized List<Integer> getDeck() {
        return deck;
    }

    public synchronized void shuffle() {
        Collections.shuffle(deck);
    }
}
