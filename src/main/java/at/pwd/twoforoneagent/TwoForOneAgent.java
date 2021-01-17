package at.pwd.twoforoneagent;

import at.pwd.boardgame.game.base.WinState;
import at.pwd.boardgame.game.mancala.MancalaGame;
import at.pwd.boardgame.game.mancala.MancalaState;
import at.pwd.boardgame.game.mancala.agent.MancalaAgent;
import at.pwd.boardgame.game.mancala.agent.MancalaAgentAction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Our agent:
 * Using an opening book to optimise early playout (only applies when agent is starting the game)
 * Using an endgame book to optimise playout performance.
 * Playout is happening heuristically (Heuristic 2)
 * Heuristic 1: (Parameter: H11 + H12): preferring moves that end in the players depot, thus granting another move.
 *      H11 is for final move decision making
 *      H12 is for the selection step
 * Heuristic 2: Improving the playout phase from random playout, to  move selection based on several parameters:
 *      H21: Value of pieces moving forward
 *      H22: How much having another move is worth
 *      H23: Value of the worst move (to ensures bad plays are still being considered)
 *
 *  (Improvements from template):
 *  * UCT value only used for choosing child node, not for choosing final move.
 *  * Ensuring every child node is selected during selection step.
 */
public class TwoForOneAgent implements MancalaAgent {

    private Random r = new Random();
    private MancalaState originalState;

    //parameters
    private static final double C = 1.0f/Math.sqrt(2.0f);
    private static final double H11 = 0.0625;
    private static final double H12 = 0.0625;
    private static final double H21 = 0.125;
    private static final double H22 = 0.5;
    private static final double H23 = 0.25;
    private static final int ENDG_DATA_LEN = 14;
    private boolean openingBookMode = false;

    private Map<ArrayList<Byte>,Integer> openingBook = new HashMap<>();

    private String endgameBookFileName = "kalaha_endgame_";
    private String openingBookFileName = "openingBook";

    private byte[][] endgameData = new byte[ENDG_DATA_LEN + 1][];

    private class MCTSTree {
        private int visitCount;
        private int winCount;

        private MancalaGame game;
        private WinState winState;
        private MCTSTree parent;
        private List<MCTSTree> children;
        String action;

        MCTSTree(MancalaGame game) {
            this.game = game;
            this.children = new ArrayList<>();
            this.winState = game.checkIfPlayerWins();
        }

        boolean isNonTerminal() {
            return winState.getState() == WinState.States.NOBODY;
        }

        MCTSTree getBestNode(boolean terminal) {
            MCTSTree best = null;
            double value = 0;
            for (MCTSTree m : children) {
                double wC = (double)m.winCount;
                double vC = (double)m.visitCount;
                double currentValue;
                double addedValue = 0;

                int action = Integer.parseInt(m.action);
                int stones = game.getState().stonesIn(m.action);

                //heuristic 1
                if(terminal){
                    System.out.println("stones: " + stones + ", action: " + action);
                    if(action < 8 && (stones == (action-1))){
                        addedValue = H11;
                    } else if(action > 8 && ((action - 8) == stones)){
                        addedValue = H11;
                    }
                    currentValue = wC / vC + addedValue;
                    System.out.println("score: " + currentValue);
                    System.out.println("win count: " + m.winCount + ", visit count: " + m.visitCount);
                } else {
                    if(action < 8 && (stones == (action-1))){
                        addedValue = H12;
                    } else if(action > 8 && ((action - 8) == stones)){
                        addedValue = H12;
                    }

                    if(game.getState().getCurrentPlayer() != originalState.getCurrentPlayer()){
                        currentValue = vC - wC / vC + C * Math.sqrt(2 * Math.log(visitCount) / vC) + addedValue;
                    } else {
                        currentValue = wC / vC + C * Math.sqrt(2 * Math.log(visitCount) / vC) + addedValue;
                    }
                }

                if (best == null || currentValue > value) {
                    value = currentValue;
                    best = m;
                }
            }

            return best;
        }

        boolean isFullyExpanded() {
            return children.size() == game.getSelectableSlots().size();
        }

        MCTSTree move(String action) {
            MancalaGame newGame = new MancalaGame(this.game);
            if (!newGame.selectSlot(action)) {
                newGame.nextPlayer();
            }
            MCTSTree tree = new MCTSTree(newGame);
            tree.action = action;
            tree.parent = this;

            this.children.add(tree);

            return tree;
        }
    }

    @Override
    public MancalaAgentAction doTurn(int computationTime, MancalaGame game) {
        long start = System.currentTimeMillis();
        this.originalState = game.getState();

        if(endgameData[0] == null){
            try {
                loadEndgameDatabase(endgameBookFileName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if(game.getState().stonesIn("1") == 0 && game.getState().stonesIn("8") == 0){
               try {
                   loadOpeningDatabase(openingBookFileName);
                   openingBookMode = true;
               } catch (IOException e) {
                   e.printStackTrace();
                   openingBookMode = false;
               }
               return new MancalaAgentAction(game.getSelectableSlots().get(0));
        }
        if(openingBookMode){
            return doOpeningTurn(game,start,computationTime);
        }

        MCTSTree root = new MCTSTree(game);

        while ((System.currentTimeMillis() - start) < (computationTime*1000 - 500)) {
            MCTSTree best = treePolicy(root);
            WinState winning = defaultPolicy(best.game);
            backup(best, winning);
        }

        MCTSTree selected = root.getBestNode(true);
        System.out.println("Selected action: " + selected.action + ", win count: " + selected.winCount +
                ", visit count: " + selected.visitCount);
        return new MancalaAgentAction(selected.action);
    }

    /***************************************************************************************
     *    Thanks to Anders Carstensen from the University of Southern Denmark for
     *    providing us with the opening and endgame book.
     ***************************************************************************************/
    private void loadOpeningDatabase(String filename) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream is = classLoader.getResourceAsStream(filename);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[is.available()];
        int nRead;

        while ((nRead = is.read(bytes, 0, bytes.length)) != -1) {
            buffer.write(bytes, 0, nRead);
        }
        bytes = buffer.toByteArray();
        parseOpeningBook(bytes);
    }

    /***************************************************************************************
     *    Thanks to Anders Carstensen from the University of Southern Denmark for
     *    providing us with the opening and endgame book.
     ***************************************************************************************/
    private void loadEndgameDatabase(String filename) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();

        try {
                for (int i = 1; i <= ENDG_DATA_LEN; i++) {
                    InputStream is = classLoader.getResourceAsStream(filename + i);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] bytes = new byte[is.available()];
                    int nRead;
                    while ((nRead = is.read(bytes, 0, bytes.length)) != -1) {
                        buffer.write(bytes, 0, nRead);
                    }
                    bytes = buffer.toByteArray();
                    endgameData[i] = bytes;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
    }


        /**
         * The Format for the openingBookData:
         * We don't need the first 4 Bytes.
         *
         * then the next Bytes are structured:
         * one byte per slot:
         * 6 bytes for your slots,
         * 1 byte for your kalaha,
         * 6 bytes for the opponents slots,
         *
         * the value of the byte is equal to the number of beans in the slot.
         * if the highest bit is set: the move leads to a winning position.
         * */
    private void parseOpeningBook(byte[] openingBookData){
        openingBook.clear();
        for (int i = 4; i + 13 < openingBookData.length; i = i +13) {
            ArrayList<Byte> position = new ArrayList<>(13);
            int bestMove = -1;
            for (int j = 0; j < 13; j++) {

                if(openingBookData[i+j] < 0){
                        bestMove = j;

                }
                position.add((byte) (openingBookData[i+j] & 0b01111111));
            }
            openingBook.put(position,bestMove);
        }
    }

    /**
     * parse a move from the opening book
     */
    private MancalaAgentAction doOpeningTurn(MancalaGame game,long start,int computationTime){
        int offset = 0;
        if(Integer.parseInt(game.getSelectableSlots().get(0)) > 8){
            offset = 7;
        }
        ArrayList<Byte> position = new ArrayList<>(13); // this is the postions which is used as a key in the hashmap.
        for (int i = 0; i < 6; i++) {
            position.add((byte) game.getState().stonesIn(Integer.toString(7 - i + offset))) ;
        }
        position.add((byte) game.getState().stonesIn(
                game.getBoard().getDepotOfPlayer(
                        originalState.getCurrentPlayer())));

        for (int i = 0; i < 6; i++) {
            position.add((byte) game.getState().stonesIn(Integer.toString(14 - i - offset)));
        }
        if(openingBook.containsKey(position)){
            return new MancalaAgentAction(Integer.toString(7-openingBook.get(position) + offset));
        } else {
            System.out.println("dropped out of Opening Book in Position: " + Arrays.toString(position.toArray()));
            openingBookMode = false;
            return doTurn((int) (computationTime - 1 - (System.currentTimeMillis() - start)/1000),game);
        }
    }

    private void backup(MCTSTree current, WinState winState) {
        boolean hasWon = winState.getState() == WinState.States.SOMEONE
                && winState.getPlayerId() == originalState.getCurrentPlayer();

        while (current != null) {
            current.visitCount++;
            current.winCount += hasWon ? 1 : 0;
            current = current.parent;
        }
    }

    private MCTSTree treePolicy(MCTSTree current) {
        while (current.isNonTerminal()) {
            if (!current.isFullyExpanded()) {
                return expand(current);
            } else {
                current = current.getBestNode(false);
            }
        }
        return current;
    }

    private MCTSTree expand(MCTSTree tree) {
        List<String> legalMoves = tree.game.getSelectableSlots();
        if(tree.children != null) {
            for (MCTSTree m : tree.children){
                legalMoves.remove(m.action);
            }
        }
        return tree.move(legalMoves.get(r.nextInt(legalMoves.size())));
    }

    private WinState defaultPolicy(MancalaGame game) {
        game = new MancalaGame(game); // copy original game
        WinState state = game.checkIfPlayerWins();

        while(state.getState() == WinState.States.NOBODY) {

            String play = null;

            do {
                int offsetForEnemySlot = 0;
                List<String> legalMoves = game.getSelectableSlots();
                // begin heuristics

                int[] stonesIn = new int[6];
                if(Integer.parseInt(legalMoves.get(0)) > 8){
                    for (int i = 0; i < stonesIn.length; i++) {
                        stonesIn[i] = game.getState().stonesIn(Integer.toString(i + 9));
                    }
                } else {
                    offsetForEnemySlot = 7;
                    for (int i = 0; i < stonesIn.length; i++) {
                        stonesIn[i] = game.getState().stonesIn(Integer.toString(i + 2));
                    }
                }

                // Checking if endgame book applies
                int sumOfStones = 0;
                for (int item:stonesIn) {
                    sumOfStones += item;
                }
                for (int i = 2; i < 9; i++) {
                    if(sumOfStones > ENDG_DATA_LEN){
                        break;
                    }
                    if(i != 8){
                        sumOfStones += game.getState().stonesIn(Integer.toString(i + offsetForEnemySlot));
                    }
                }
                int[] board = Arrays.copyOf(stonesIn,11);
                for (int i = 2; i < 7; i++) {
                    board[i+4] = game.getState().stonesIn(Integer.toString(i + offsetForEnemySlot));
                }
                if(sumOfStones <= ENDG_DATA_LEN){
                    int score = endgameData[sumOfStones][getPosOfBoardInEndgData(board)] + game.getState().stonesIn(game.getBoard().getDepotOfPlayer(game.getState().getCurrentPlayer())) - game.getState().stonesIn(game.getBoard().getDepotOfPlayer(1 - game.getState().getCurrentPlayer())) ; // + mein mancala - gegners mancala
                    if(score > 0){
                        return new WinState(WinState.States.SOMEONE,game.getState().getCurrentPlayer());
                    } else {
                        return new WinState(WinState.States.SOMEONE,1 - game.getState().getCurrentPlayer());
                    }
                }


                double[] valueOfMove = new double[6];
                for (int i = 0; i < valueOfMove.length; i++) {
                    while(stonesIn[i] > 0){
                        if(stonesIn[i] == i + 1){
                            valueOfMove[i] += stonesIn[i]*H21+H22;
                            break;
                        }
                        if(stonesIn[i] < i + 1){
                            valueOfMove[i] += stonesIn[i]*H21;
                            if(stonesIn[i - stonesIn[i]] == 0){
                                valueOfMove[i] += game.getState().stonesIn(Integer.toString(i+offsetForEnemySlot + 2))*2 + (i+1)*H21;
                            }
                            break;
                        }
                        if(stonesIn[i] > i + 1){
                            if(stonesIn[i] > i + 12){
                                valueOfMove[i] -= 12*(1-i*H21);
                                valueOfMove[i]++;
                                stonesIn[i] -= 12;
                            } else {
                                valueOfMove[i] += (i+1)*H21 - (stonesIn[i] - (i+1))*(1-2*H21);
                                break;
                            }
                        }
                    }
                }
                double min = 1024;
                for (double item:valueOfMove) {
                    if(item < min && item != 0){
                        min = item;
                    }
                }
                if(min < 0){
                    for (int i = 0; i < valueOfMove.length; i++) {
                        if(valueOfMove[i] != 0){
                            valueOfMove[i] -= min - H23;
                        }
                    }
                }
                double sum = 0;
                for (double item:valueOfMove) {
                    sum+= item;
                }
                double randomValue = r.nextDouble()*sum;
                for (int i = 0; i < valueOfMove.length; i++) {
                    sum -= valueOfMove[i];
                    if(randomValue > sum){
                        if(offsetForEnemySlot > 0){
                            play = Integer.toString(i + 2);
                        } else {
                            play = Integer.toString(i + 9);
                        }


                        break;
                    }
                }
                if(min == 1024){
                    play = legalMoves.get(r.nextInt(legalMoves.size()));
                }
                if(play == null){
                    System.out.println("error in heuristic calculation logic");
                    play = legalMoves.get(r.nextInt(legalMoves.size()));
                }

            } while(game.selectSlot(play));
            game.nextPlayer();

            if(game.getState().stonesIn(game.getBoard().getDepotOfPlayer(game.getState().getCurrentPlayer())) > 36){
                return new WinState(WinState.States.SOMEONE,game.getState().getCurrentPlayer());
            }
            state = game.checkIfPlayerWins();

        }

        return state;
    }

    private int getPosOfBoardInEndgData(int[] board) {
        int ret = 0;
        int c = 0;
        for (int i = 0; i < 11;) {
            c += board[i];
            i++;
            ret += binomial(c, i);
            c++;
        }
        return ret;
    }

    private int binomial(int n, int k) {
        int r = 1, d;
        if (k > n)
            return 0;
        for (d = 1; d <= k; d++) {
            r *= n--;
            r /= d;
        }
        return r;
    }

    @Override
    public String toString() {
        return "Two For One Agent";
    }
}
