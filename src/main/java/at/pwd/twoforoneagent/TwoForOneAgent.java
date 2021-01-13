package at.pwd.twoforoneagent;


import at.pwd.boardgame.game.base.WinState;
import at.pwd.boardgame.game.mancala.MancalaGame;
import at.pwd.boardgame.game.mancala.MancalaState;
import at.pwd.boardgame.game.mancala.agent.MancalaAgent;
import at.pwd.boardgame.game.mancala.agent.MancalaAgentAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Changes:
 * UCT algorithm only used for choosing child node, not for choosing final move.
 * Heuristic 1: (Parameter: H11 + H12): preferring moves that end in the players depot, thus granting another move.
 *      H11 is for final move decision making, H12 is for the selection step
 */
public class TwoForOneAgent implements MancalaAgent {

    private Random r = new Random();
    private MancalaState originalState;

    //parameters
    private static final double C = 1.0f/Math.sqrt(2.0f);
    private static final double H11 = 0.25; // die Werte find ich cool, aber ich glaub sie sollen ziemlich klein sein, vielleicht 0.0625 oder 0.125 und
    private static final double H12 = 0.1; // gegen Ende der Berechnungszeit sollen die 2 Werte auf jeden Fall gleich sein,
    private static final double H21 = 0.125; // Wie viel mehr wert sind sie Steine wenn sie 1 Feld weiter vorne sind.
    private static final double H22 = 0.5; // Wie gut ist es nochmal dranzukommen.
    private static final double H23 = 0.25; // Welchen Wert soll der schlechteste Zug haben.
    private static final int ENDG_DATA_LEN = 14; // länge der endspieldatenbank

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


                if(terminal){
                    System.out.println("stones: " + stones + ", action: " + action);

                    if(action < 8 && (stones == (action-1))){
                        addedValue = H11;
                    } else if(action > 8 && ((action - 8) == stones)){
                        addedValue = H11;
                    }
                    currentValue = wC / vC + addedValue;
                    System.out.println("score: " + currentValue);

                } else {
                    if(action < 8 && (stones == (action-1))){
                        addedValue = H12;
                    } else if(action > 8 && ((action - 8) == stones)){
                        addedValue = H12;
                    }
                    currentValue = wC / vC + C * Math.sqrt(2 * Math.log(visitCount) / vC) + addedValue;
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

        MCTSTree root = new MCTSTree(game);

        while ((System.currentTimeMillis() - start) < (computationTime*1000 - 100)) {
            MCTSTree best = treePolicy(root);
            WinState winning = defaultPolicy(best.game);
            backup(best, winning);
        }

        MCTSTree selected = root.getBestNode(true);
        System.out.println("Selected action: " + selected.action + ", win count: " + selected.winCount +
                ", visit count: " + selected.visitCount);
        return new MancalaAgentAction(selected.action);
    }

    private void backup(MCTSTree current, WinState winState) {
        boolean hasWon = winState.getState() == WinState.States.SOMEONE && winState.getPlayerId() == originalState.getCurrentPlayer();

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
            int offsetForEnemySlot = 0;
            do {
                List<String> legalMoves = game.getSelectableSlots();
                // anfang heuristik block /
                /* Schauen ob wir schon im endspiel sind, damit die endspieldatenbank greifen kann.
                int sumOfStones = 0;
                for (int i = 2; i < 15; i++) {
                    if(i != 8){
                        sumOfStones += game.getState().stonesIn(Integer.toString(i));
                    }
                    if(sumOfStones > ENDG_DATA_LEN){
                        break;
                    }
                }
                if(sumOfStones <= ENDG_DATA_LEN){
                    // schau ma in der datenbank nach und fertig.
                }
                */

                int listindex = 0;
                int[] stonesIn = new int[6];
                for (int i = 0; i < 6; i++) {
                    if(Integer.parseInt(legalMoves.get(listindex)) == i + 2){
                        stonesIn[i] = game.getState().stonesIn(legalMoves.get(listindex));
                        listindex++;
                        offsetForEnemySlot = 7;
                    }
                    if(Integer.parseInt(legalMoves.get(listindex)) == i + 9){
                        stonesIn[i] = game.getState().stonesIn(legalMoves.get(listindex));
                        listindex++;
                        offsetForEnemySlot = -7;
                    }
                }

                //System.out.println(Arrays.toString(stonesIn));
                double[] valueOfMove = new double[6]; // mein gedanke hier ist: wenn man nochmal ziehen darf: +0,5 und wenn man einen stein um ein feld näher zum mancala bewegt +0.125
                for (int i = 0; i < valueOfMove.length; i++) {
                    while(stonesIn[i] > 0){
                        if(stonesIn[i] == i + 1){
                            valueOfMove[i] += stonesIn[i]*H21+H22;
                            break;
                        }
                        if(stonesIn[i] < i + 1){
                            valueOfMove[i] += stonesIn[i]*H21;
                            if(stonesIn[i - stonesIn[i]] == 0){
                                valueOfMove[i] += stonesIn[i+offsetForEnemySlot]*2 + (i+1)*H21;
                            }
                            break;
                        }
                        if(stonesIn[i] > i + 1){
                            if(stonesIn[i] > i + 12){
                                valueOfMove[i] -= 12*(1-i*H21);
                                valueOfMove[i]++;
                                stonesIn[i] -= 12;
                            } else {
                                valueOfMove[i] += (i+1)*H21 - ((i+1) - stonesIn[i])*(1-2*H21); // hier könnte man noch elaborieren
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
                for (int i = 0; i < valueOfMove.length; i++) {
                    if(valueOfMove[i] != 0){
                        valueOfMove[i] += min + H23; // es gibt eine chance, dass schlechte züge gespielt werden, aber nur ein sehr geringe.
                        //System.out.println(i + ": " + valueOfMove[i]);
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
                        play = Integer.toString(i);
                        break;
                    }
                }
                if(play == null){
                    System.out.println("error in heuristic calculation logic");
                    play = legalMoves.get(r.nextInt(legalMoves.size()));
                }
                // ende heuristikblock /
                // play = legalMoves.get(r.nextInt(legalMoves.size()));
            } while(game.selectSlot(play));
            game.nextPlayer();

            state = game.checkIfPlayerWins();
        }

        return state;
    }

    @Override
    public String toString() {
        return "Two For One Agent";
    }
}
