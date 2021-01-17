### TwoForOneAgent

* Tobias Jakob
* Nikolaus Stratil-Sauer

Our agent is built upon the MCTS template provided by the lecture. We made several improvements to this template, like ensuring that every possible child node is 
selected in the selection step.

We also implemented two heuristics to improve playing performance of our agent. The first heuristic gives turns that end in the players mancala a higher value, because
these turns are generally preferable. The second heuristic improves the playout phase of MCTS. Instead of relying on random playout we are using a system that gives
actions a value, based on a heuristic evaluation. This is considering several factors, like if the action ends in a player's own mancala or how many stones are moved. 

Also we implemented an opening book and an endgame database. We got those from Anders Carstensen from the University of Southern Denmark (http://kalaha.krus.dk/). The opening
book is only used when our agent is starting the game. It tells the agent which move to take, given a board state until the agent drops out of this data set. The endgame databook
on the other hand is only used in the playout step, as it helps to determine early which player is going to win a round. Thus it improves playout performance by a lot.
