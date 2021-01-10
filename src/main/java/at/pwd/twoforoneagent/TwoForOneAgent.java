package at.pwd.twoforoneagent;


import at.pwd.boardgame.game.base.WinState;
import at.pwd.boardgame.game.mancala.MancalaGame;
import at.pwd.boardgame.game.mancala.MancalaState;
import at.pwd.boardgame.game.mancala.agent.MancalaAgent;
import at.pwd.boardgame.game.mancala.agent.MancalaAgentAction;

import java.util.ArrayList;
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
    private static final double H11 = 0.25;
    private static final double H12 = 0.1;

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
            String play;
            do {
                List<String> legalMoves = game.getSelectableSlots();

                play = legalMoves.get(r.nextInt(legalMoves.size()));
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
