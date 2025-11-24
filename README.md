# Chess
Even though, this program is a semi functional chess engine, it is primarily an illustration of the Static Programming principles.
Nonetheless, I hope, somebody would turn it into a working chess engine.
# Static Programming
Term Static Programming used primarily to oppose Dynamic Programming, even though, strictly speaking, it is a subset of Dynamic Programming.
Applying certain limitations to Dynamic Programming we are getting certain benefits in return. So, this technique deserves a separate term for it.
## Considerations
 - The state has to contain the information that is sufficient, within the context of a super program, to determine the new states that the current state is evolving into.
 - Super program assume the role of interpreter of the state and from this perspective the stored state can be called a program.
 - Time that is required to determine a new states is not free. So it makes sense for the super program to track how much time it spent developing each state.
 - While developing a new states, the network is formed. So it makes sense to use this network to distribute compute power for further state development.
 - Once the state is computed it is no longer changing, creating a static snapshot, thus the term Static Programming
## Benefits
 - If the information stored in the state is enough to define some loss function, the compute distribution can be preferential.
 - Although the states themselves are static, the network they are forming is dynamic and can add/remove edges if the super program changes.
 - The super program change is barely affecting the network that is already formed. It practically means that no re-training is needed.
# Chess Engine
Board.kt contains all the tools to define the chess board aka The State
 - Piece is an abstract class defining a piece. EmptySq, Pawn, Rook, Bishop, Knight, King, Queen are Piece implementations.
 - Board, BoardSerializer, BoardDeserializer are self explanatory
BoardLinker.kt contains the engine logic aka The Super Program
 - Turn defining the transitions between the boards expressing the possible move. Meant to be internal to BoardLinker.
 - BoardsStorage is the interface abstraction for loss coupling.
 - BoardLinker is the flexible wrapper around the Board class. It contains the possible Turns which have weights. This weight defines compute distribution and eventually the turn decision.
 - BoardProcessor is the compute distribution mechanism.
 
