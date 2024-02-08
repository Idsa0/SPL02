package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Vector;
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
    
    // TODO: lock class wrapper.
    protected final Object[] playerLocks;

	private Object declareSetLock;

	private boolean checkSet;
    
    
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
        
        // Java does not allow an array of vectors to be created. Must be casted.
		this.playerTokens = (Vector<Integer>[]) new Vector[env.config.players];
		this.playerLocks = new Object[env.config.players];
		
		for (int i = 0 ; i< env.config.players; i++) {
			playerTokens[i] = new Vector<Integer>();
			playerLocks[i] = new Object();
		}
		
		declareSetLock = new Object();
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
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
    	if (!legalSlot(slot))
			throw new RuntimeException();
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        env.ui.placeCard(card, slot);

        // TODO: synchronized?
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
    	if (!legalSlot(slot))
			throw new RuntimeException();
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        if (slotToCard[slot] != null)
            cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;

        env.ui.removeCard(slot);
        // TODO: synchronized?
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
    	synchronized(playerLocks[player]) {
    		if (!legalSlot(slot))
    			throw new RuntimeException();
    		
    		if (tokenAmount(player) >= 3) // TODO: magic number, is this effected by config?
    			throw new RuntimeException(); // TODO: maybe exit simply.
    		
    		playerTokens[player].add(slot);
    		env.ui.placeToken(player, slot);
    	}
    }

    private boolean legalSlot(int slot) {
    	// TODO: we threw exceptions where it wasn't legal. but at the end of the game it is not so clear which slots remain to play. 
		// TODO Auto-generated method stub
		return false;
	}

	/**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
    	synchronized(playerLocks[player]) {
    		if (!legalSlot(slot))
    			throw new RuntimeException();
    		
    		if (tokenAmount(player) <= 0) // TODO: magic number, is this effected by config?
    			return false;
    		
    		if (playerTokens[player].contains(slot)){
    			playerTokens[player].remove(slot);
    			env.ui.removeToken(player, slot);
    			return true;
    		}
    		
    		return false;
    	}
    }

	public int tokenAmount(int player) {
		synchronized (playerLocks[player]) {
			return playerTokens[player].size();
		}	
	}

	public void declareSet(int id) {
		synchronized(declareSetLock) {
			try {
				// Declare that i want the set to be checked
				// TODO: concurrency error: what if the dealer finishes his check and notifiyes before we get to wait()? this will lock forever.
				// then wait
			} 
		}
		// TODO Auto-generated method stub
		
	}
}
