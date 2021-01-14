package at.pwd.twoforoneagent;


import at.pwd.boardgame.game.base.WinState;
import at.pwd.boardgame.game.mancala.MancalaGame;
import at.pwd.boardgame.game.mancala.MancalaState;
import at.pwd.boardgame.game.mancala.agent.MancalaAgent;
import at.pwd.boardgame.game.mancala.agent.MancalaAgentAction;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

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
    private static final double H11 = 0.0625; // die Werte find ich cool, aber ich glaub sie sollen ziemlich klein sein, vielleicht 0.0625 oder 0.125 und
    private static final double H12 = 0.0625; // gegen Ende der Berechnungszeit sollen die 2 Werte auf jeden Fall gleich sein,
    private static final double H21 = 0.125; // Wie viel mehr wert sind sie Steine wenn sie 1 Feld weiter vorne sind.
    private static final double H22 = 0.5; // Wie gut ist es nochmal dranzukommen.
    private static final double H23 = 0.25; // Welchen Wert soll der schlechteste Zug haben.
    private static final int ENDG_DATA_LEN = 14; // länge der endspieldatenbank
    private static boolean IStart = true;
    private static boolean openingBookMode = false;

    private static Map<ArrayList<Byte>,Integer> openingBook = new HashMap<>();


    private static String openingBookFileName = "C:\\Users\\tobij\\OneDrive\\Dokumente\\uni\\Strategy\\src\\main\\java\\at\\pwd\\twoforoneagent\\opening-book-standard-allopenings-2fullmove.zip";

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
                    System.out.println("win count: " + m.winCount + ", visit count: " + m.visitCount);
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

        // start eröffnungsbuchblock
        if(IStart){
            System.out.println(game.getState().stonesIn("1") + " " + game.getState().stonesIn("8"));
           if(game.getState().stonesIn("1") == 0 && game.getState().stonesIn("8") == 0){
               try {
                   load(openingBookFileName);
                   openingBookMode = true;
               } catch (IOException e) {
                   e.printStackTrace();
                   openingBookMode = false;
               }
           }
           IStart = false;
        }
        if(openingBookMode){
            return doOpeningTurn(game,start);
        }
        // ende eröffnungsbuchblock

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

    private void load(String filename) throws IOException {
        var zipPath = Paths.get(filename);
        FileSystems.newFileSystem(zipPath,  ClassLoader.getPlatformClassLoader()).getRootDirectories().forEach(root -> {
            try {
                var firstPath = Files.walk(root).filter(x -> Files.isRegularFile(x)).findFirst().get();
                byte[] openingBookData = Files.readAllBytes(firstPath);
                parseOpeningBook(openingBookData);
            } catch (Exception e) {
                throw new RuntimeException(e); // da überleg ma si no was
            }
        });
    }
    private void parseOpeningBook(byte[] openingBookData){
        openingBook.clear();
        for (int i = 4; i < openingBookData.length; i = i +13) {
            ArrayList<Byte> position = new ArrayList<>(13);
            int bestMove = -1;
            for (int j = 0; j < 13; j++) {
                if(openingBookData[i+j] < 0){
                    if(bestMove == -1) {
                        bestMove = j;
                    }
                }
                position.add((byte) (openingBookData[i+j] & 0b01111111));
            }
            openingBook.put(position,bestMove);
        }
    }

    private MancalaAgentAction doOpeningTurn(MancalaGame game,long start){
        int offset = 0;
        if(Integer.parseInt(game.getSelectableSlots().get(0)) > 8){
            offset = 7;
        }
        ArrayList<Byte> position = new ArrayList<>(13);
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
            return doTurn((int) (9 - (System.currentTimeMillis() - start)/1000),game);
        }
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

            do {
                int offsetForEnemySlot = 0;
                List<String> legalMoves = game.getSelectableSlots();
                // anfang heuristik block /
                /* Schauen ob wir schon im endspiel sind, damit die endspieldatenbank greifen kann.
             ============ Datenbanken auf einem neuen Branch. ============
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
                                valueOfMove[i] += (i+1)*H21 - (stonesIn[i] - (i+1))*(1-2*H21); // hier könnte man noch elaborieren, weil er checkt nicht wenn steine wieder auf deine Seite gehen
                                break;                                                         // und das könnt scho relevant sein weil ma ja die Steine vom anderen schlagen kann.
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
                            valueOfMove[i] -= min - H23; // es gibt eine chance, dass schlechte züge gespielt werden, aber nur ein sehr geringe.
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




                // ende heuristikblock /
                play = legalMoves.get(r.nextInt(legalMoves.size()));
            } while(game.selectSlot(play));

            if(game.getState().stonesIn(game.getBoard().getDepotOfPlayer(1)) > 36){
                return new WinState(WinState.States.SOMEONE,1);
            }
            if(game.getState().stonesIn(game.getBoard().getDepotOfPlayer(0)) > 36){
                return new WinState(WinState.States.SOMEONE,0);
            }


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
