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

    fun readLinker(path:String): BoardLinker {
        val json = IOUtils.toString(
            this.javaClass.getResourceAsStream(path),
            "UTF-8"
        )
        return mapper.readValue(json, BoardLinker::class.java)
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

        val b = readLinker("/test.json")
        b.b.print()
        b.b.evalBoard(true)
        b.b.develop(true){ (kk,bb) ->
            println()
            Turn.printKey(bb,kk)
            bb.print()
            println()
        }
        b.b.evalBoard(false)
        b.b.develop(false){ (kk,bb) ->
            println()
            Turn.printKey(bb,kk)
            bb.print()
            println()
        }
    }

    @Test
    fun testPropagate() {
        val b = readLinker("/test.json")
        val bp = BoardProcessor(RamStorage())
        var board = b.b
        var white = b.w
        for (k in 1..20) {
            bp.clearStorage()
            for (j in 1..20) {
                for (i in 1..100) {
                    bp.sinkTicks(100000, board, white)
                }
                bp.printStats()
                bp.reevaluateWeights(board, white)
                bp.printJustWeights(board, white)
                //should produce exactly the same numbers!!! for debug only
                bp.reevaluateWeights(board, white)
                bp.printJustWeights(board, white)
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