import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.commons.io.IOUtils
import org.example.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BoardLinkerTest {

    val mapper = ObjectMapper().registerKotlinModule()

    fun readBoard(path:String): Board {
        val json = IOUtils.toString(
            this.javaClass.getResourceAsStream(path),
            "UTF-8"
        )
        return mapper.readValue(json, Board::class.java)
    }

    @BeforeEach
    fun setup() {
        val simpleModule = SimpleModule(
            "SimpleModule",
            Version(1, 0, 0, null)
        )
        simpleModule.addSerializer(BoardSerializer())
        simpleModule.addDeserializer(Board::class.java, BoardDeserializer())
        mapper.registerModule(simpleModule)
    }

    @Test
    fun testArrFun() {

        val b = readBoard("/test.json")
        b.print()
        b.evalBoard(true)
        b.develop(true){ (kk,bb) ->
            println()
            Turn.printKey(bb,kk)
            bb.print()
            println()
        }
        b.evalBoard(false)
        b.develop(false){ (kk,bb) ->
            println()
            Turn.printKey(bb,kk)
            bb.print()
            println()
        }
    }

    @Test
    fun testPropagate() {
        val bp = BoardProcessor(RamStorage())
        //6k1/3q1pp1/p4n1p/Pp2p1n1/1Pp1P3/2P1Q1PP/2B1NP2/5K2 w - - 0 26
        var board = readBoard("/test.json")
        var white = true
        for (k in 1..20) {
            bp.clearStorage()
            for (j in 1..20) {
                for (i in 1..100) {
                    bp.sinkTicks(5000, board, white)
                }
                bp.printStats()
                bp.reevaluateWeights(board, white)
                bp.printJustWeights(board, white)
                //should produce exactly the same numbers!!! for debug only
                //bp.reevaluateWeights(board, white)
                //bp.printJustWeights(board, white)
            }
            val turn = bp.turn(board,white)
            board = turn.first
            white = !white
            Turn.printKey(board, turn.second)
            board.print()
            if (bp.winOrLost(board,white)) {
                println("wl")
                break
            }
            if (board.lost(true) || board.lost(false)) {
                break
            }
        }
    }
}