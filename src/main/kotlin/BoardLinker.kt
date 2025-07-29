package org.example

import org.example.BoardLinker.Companion.WIN_WEIGHT
import java.util.concurrent.ConcurrentHashMap

class Turn(val key:Int, val hash:Long,
           var weight:Double,
           var weightTimestamp:Long = 0L//timestamp of a last count
        ){
    companion object {
        const val mask3b = 7
        fun printKey(b:Board, key:Int) {
            var k = key
            val toy = k.and(mask3b)
            k = k.shr(3)
            val tox = k.and(mask3b)
            k = k.shr(3)
            val fy = k.and(mask3b)
            k = k.shr(3)
            val fx = k.and(mask3b)
            k = k.shr(3)
            val pk = k.and(mask3b)
            b.get(tox,toy) { pc ->
                println(" ${pc.code()}(${fx},${fy})(${tox},${toy})")
            }

        }
    }
    fun isNotDone():Boolean {
        return weight > BoardLinker.LOST_WEIGHT && weight < BoardLinker.WIN_WEIGHT
    }
}

interface BoardsStorage {
    fun defineBoard(b:Board, w:Boolean):Int
    fun updateLinker(bl:BoardLinker)
    fun propagateTicks(tickCount:Int, board_hash:Long)
    fun getLinker(board_hash:Long, cb:Acceptor<BoardLinker>)
}

enum class LinkerState {    DEFINED, //just created, no subsequent turns calculated
                            WON, //evaluation leads to win
                            LOST, //evaluation leads to loss
                            LINKED //all subsequent turns calculated
}

class BoardLinker(val b:Board, val w:Boolean, val boardWeight:Int,
                  val turns:MutableList<Turn>,
    //stats, turned back to vars for storage concerns
    //it is dangerous to use vars here... it's done only for the demo purposes
                  var state:LinkerState = LinkerState.DEFINED,
                  var childCount:Long = 0L,//timestamp of a last count
) {
    var tickAccumulated:Int = 0//ticks accumulated currently
    var tickProcessed:Long = 0L//total count of ticks consumed
    fun setTurns(t:List<Turn>) {
        turns.clear()
        turns.addAll(t)
        state = LinkerState.LINKED
    }
    fun setStt(s:LinkerState) {
        state = s
    }
    //propagation
    fun propagateTicks(tickCount:Int, bs:BoardsStorage) {
        tickAccumulated += tickCount
        processTicks(bs)
    }
    fun processTicks(bs:BoardsStorage) {
        //TODO: return ticks for won/lost
        if (state == LinkerState.DEFINED){
            if (tickAccumulated >= LINK_COST) {
                tickAccumulated -= LINK_COST
                tickProcessed += LINK_COST
                developMoves(bs)
            }
        }
        if (state == LinkerState.LINKED) {
            processTicksLinked(bs)
        }
    }
    fun getMinMove(bs:BoardsStorage):Double {
        var min = WIN_WEIGHT.toDouble()
        for (t in turns) {
            if (t.weight < min) {
                min = t.weight
            }
        }
        return min
    }
    fun developMoves(bs:BoardsStorage):Double {
        val out = mutableListOf<Turn>()
        var min = WIN_WEIGHT
        b.develop(w) { (kk,bb) ->
            val ww = bs.defineBoard(bb,!w)
            if (ww < min) {
                min = ww
            }
            out.add(Turn(kk,bb.calcHash(!w),ww.toDouble()))
        }
        if (min == LOST_WEIGHT) {
            setStt(LinkerState.WON)
            return WIN_WEIGHT.toDouble()
        } else {
            setTurns(out)
        }
        return min.toDouble()
    }
    fun getFlatWeight(bs:BoardsStorage):Double {
        return when(state) {
            LinkerState.LOST -> LOST_WEIGHT.toDouble()
            LinkerState.DEFINED -> developMoves(bs)
            LinkerState.WON -> WIN_WEIGHT.toDouble()
            LinkerState.LINKED -> getMinMove(bs)
        }
    }

    fun processTicksLinked(bs:BoardsStorage) {
        if (tickAccumulated >= turns.size*TURNS_MULT) {
            var budget = 0.0
            for (t in turns) {
                if (t.isNotDone()) {
                    budget += 1/t.weight
                }
            }
            val b_chunk = tickAccumulated/budget
            for (t in turns) {
                if (t.isNotDone()) {
                    val sz = (b_chunk/t.weight).toInt()
                    tickAccumulated -= sz
                    tickProcessed += sz
                    bs.propagateTicks(sz, t.hash)
                }
            }
        }
    }
    fun iterateTurns(ts:Long, bs:BoardsStorage, cb: Acceptor<Pair<Turn, BoardLinker>>) {
        for (t in turns) {
            //todo check if turn possible to eliminate hash dups
            //alternatively return board linker instead
            bs.getLinker(t.hash) { bl ->
                cb(Pair(t,bl))
            }
        }
    }
    companion object {
        const val LINK_COST = 100
        const val TURNS_MULT = 10
        const val WIN_WEIGHT = 10000
        const val LOST_WEIGHT = 1
    }
}

class  RamStorage<K,V>:GenericStorage<K,V> {

    private val cache = ConcurrentHashMap<K, V>()

    override fun load(key: K, cb: Acceptor<V>, cbk:Acceptor<K>) {
        val r = cache[key]
        if (r != null) {
            cb(r)
        } else {
            cbk(key)
        }
    }

    override fun store(key: K, value: V) {
        cache[key] = value
    }

    fun each(cb:Acceptor<Pair<K,V>>) {
        for ((k,v) in cache) {
            cb(Pair(k,v))
        }
    }
    fun clear() {
        cache.clear()
    }
}

class BoardProcessor(val storage:GenericStorage<Long,BoardLinker>):BoardsStorage {
    fun getLinker(b: Board, w: Boolean, cb:Acceptor<BoardLinker>) {
        storage.load(b.calcHash(w), cb) { blk ->
            //evaluate win/lost here might be bad idea...
            val bs = boardState(b,w)
            val wght:Int = when(bs) {
                LinkerState.LOST -> BoardLinker.LOST_WEIGHT
                LinkerState.WON -> BoardLinker.WIN_WEIGHT
                else -> b.evalBoard(w)
            }
            val out = BoardLinker(b, w, wght, mutableListOf(), bs)
            storage.store(b.calcHash(w), out)
            cb(out)
        }
    }

    fun clearStorage() {
        if (storage is RamStorage) {
            storage.clear()
        }
    }
    //evaluation

    fun reevaluateWeights(b: Board, w: Boolean) {

        var callCount = 0 //for debug only

        fun avgNextTurn(visited:Array<Long>, bl: BoardLinker, ts:Long):Double {
            callCount++
            if (bl.state == LinkerState.LINKED) {
                if (bl.tickProcessed > TICK_THRESHOLD) {
                    val weights = mutableListOf<Triple<Double,Turn,Int>>()
                    bl.iterateTurns(ts, this) { (t,bbl) ->
                        if (visited.contains(t.hash)) {
                            val fw = bbl.getFlatWeight(this)
                            weights.add(addBoardWeight(fw, t, bbl))
                        } else {
                            val fw = avgNextTurn(visited.plus(t.hash), bbl, ts)
                            weights.add(addBoardWeight(fw, t, bbl))
                        }
                    }
                    //current approach seem to be very aggressive, resulting in foolish attacks
                    //it seem kinda expected since i don't include the opponent weights in
                    //current board weight... but i do adjustments over next turns board weights that
                    //should compensate for it... some adjustments necessary... but i still think
                    // - evaluate only your pieces at your turn is the way to set weights
                    weightTurns(bl.boardWeight, weights)
                    //return min weight
                    var wght = WIN_WEIGHT.toDouble()
                    for (ww in bl.turns) {
                        if (ww.weight < wght) {
                            wght = ww.weight
                        }
                    }
                    return wght
                }
            }
            return bl.getFlatWeight(this)
        }

        //go deepest from current, keep track for loops
        //todo - passing and castling should be checked here
        //       because of it keep the history from the match beginning
        getLinker(b,w) { bl ->
            val visited = arrayOf(b.calcHash(w))
            avgNextTurn(visited,bl,time())
        }

        println("callCount ${callCount}")
    }

    fun avoidNullSum(sum:Double):Double {
        val delta = 0.001
        if (kotlin.math.abs(sum) <= delta) {
            return delta
        }
        return sum
    }


    fun addBoardWeight(w:Double, t:Turn, bl:BoardLinker):Triple<Double,Turn,Int> {
        return Triple(w,t,bl.boardWeight)
    }
    fun weightTurns(bw:Int, l:List<Triple<Double,Turn,Int>>) {
        var sum = 0.0
        for (w in l) {
            sum += w.first
            if (w.first < 0) {
                //should have no negative values
                println(w.first)
            }
        }
        sum = sum/l.size
        //we only alter the weights that have been developed
        //originally, i wanted to do w.first*w.third/sum to prefer min min direction, but it worked poorly
        //sum*w.third/w.first seem to work well.... basically we give the preferrence to the min opponent
        //  weight that would have a best minimum for our next turn...
        //  - i tried best maximum, but it also worked poorly... keeping minimum seem to have the most sense anyways,
        //    since the opponent would most likely pick this direction anyways
        for (w in l) {
            w.second.weight = sum*w.third/w.first
        }
        //each turn board weight need to be scaled proportional to next turn min
        //we want to redistribute weight so the sum of board weights is the same and positive
        // !!! after the weight adjustment the resulting weight sum should be the same (or very close)
    }
    fun sumWghtAvg(bw:Int, l:List<Triple<Double,Long,Int>>):Pair<Double,Long> {
        var sum = 0.0
        //var min = 0.0
        var count = 0L
        for (w in l) {
            sum += w.first
            /*if (w.first < min) {
                min = w.first
            }*/
            count += w.second
        }
        sum = sum/l.size //adj zero and normalize
        //each weight should contribute proportionaly to its avg
        var aSum = 0.0
        for (w in l) {
            //aSum += ((w.first-min)/avoidNullSum(sum))*w.third
            aSum -= (w.first/avoidNullSum(sum))*(bw-w.third)
            count += w.second
        }
        return Pair(aSum/l.size,count)
    }
    fun sumWghtAvg2(bw:Int, l:List<Triple<Double,Long,Int>>):Pair<Double,Long> {
        var min = 10000000.0
        var bww = 0
        var count = 0L
        for (w in l) {
            if (w.first < min) {
                min = w.first
                bww = w.third
            }
            count += w.second
        }
        return Pair(((bw-bww)-min)/2,count)
    }
    fun fastAvg(bw:Int, l:List<Pair<Double,Long>>):Pair<Double,Long> {
        var min = 10000000.0
        var count = 0L
        for (w in l) {
            if (w.first < min) {
                min = w.first
            }
            count += w.second
        }
        return Pair(bw.toDouble()-min,count)
    }
    fun fastAvgMax(bw:Int, l:List<Pair<Double,Long>>):Pair<Double,Long> {
        var max = -10000000.0
        var count = 0L
        for (w in l) {
            if (w.first > max) {
                max = w.first
            }
            count += w.second
        }
        return Pair(bw.toDouble()-max,count)
    }
    fun fastAvg1(bw:Int, l:List<Pair<Double,Long>>):Pair<Double,Long> {
        var sum = 0.0
        var count = 0L
        for (w in l) {
            sum += w.first
            count += w.second
        }
        return Pair(bw.toDouble()-(sum/l.size),count)
    }
    fun sinkTicks(tickCount:Int, b: Board, w: Boolean) {
        getLinker(b,w) { bl ->
            bl.propagateTicks(tickCount, this)
        }
    }

    override fun defineBoard(b: Board, w: Boolean):Int {
        var weight = 0
        getLinker(b,w) { bb ->
            weight = bb.boardWeight
        }
        return weight
    }

    override fun updateLinker(bl: BoardLinker) {
        storage.store(bl.b.calcHash(bl.w), bl)
    }

    override fun propagateTicks(tickCount: Int, board_hash: Long) {
        storage.loadSafe(board_hash) { bl ->
            bl.propagateTicks(tickCount, this)
        }
    }
    override fun getLinker(board_hash:Long, cb:Acceptor<BoardLinker>) {
        storage.loadSafe(board_hash, cb)
    }

    fun time(): Long {
        return System.currentTimeMillis()
    }
    fun turn(b: Board, w: Boolean):Pair<Board,Int> {
        var min = 1000000000.0
        var board = b
        var turn = 0
        getLinker(b,w) { bl ->
            for (t in bl.turns) {
                if (t.weight < min) {
                    storage.loadSafe(t.hash) { bbl ->
                        min = t.weight
                        board = bbl.b
                        turn = t.key
                    }
                }
            }
        }
        println("min - "+min)
        return Pair(board,turn)
    }
    fun printWeights(b: Board, w: Boolean) {
        getLinker(b,w) { bl ->
            println(" [${bl.boardWeight} ${bl.childCount}] ")
            for (t in bl.turns) {
                storage.loadSafe(t.hash) { bbl ->
                    print(" (${t.weight} ${bbl.boardWeight} ${bbl.childCount} ${bbl.tickProcessed}) ")
                    Turn.printKey(bbl.b, t.key)
                    bbl.b.print()
                }
            }
            println()
        }
    }

    fun winOrLost(b: Board, w: Boolean):Boolean {
        var r = false
        getLinker(b, w) { bl ->
            if (bl.state == LinkerState.WON || bl.state == LinkerState.LOST) {
                r = true
            }
        }
        return r
    }
    fun printJustWeights(b: Board, w: Boolean) {
        getLinker(b,w) { bl ->
            for (t in bl.turns) {
                storage.loadSafe(t.hash) { bbl ->
                    print(String.format(" %.4f|", t.weight)+bbl.boardWeight)
                }
            }
            println()
        }
    }

    fun printStats() {
        if (storage is RamStorage) {
            var count = 0
            var lost = 0
            var linked = 0
            storage.each { (k,v) ->
                count++
                //v.b.print()
                if (v.state == LinkerState.LOST) {
                    //println("board ${v.w} ${v.turns.size} ${v.tickProcessed} ${v.tickAccumulated}")
                    //v.b.print()
                    lost++
                }
                if (v.state == LinkerState.LINKED) {
                    linked++
                }
            }
            println("total ${count} ${linked} ${lost}")
        }
    }
    companion object {
        const val TICK_THRESHOLD = 5000
        const val WEIGHT_BIAS = 0.1
        fun boardState(b: Board, w: Boolean):LinkerState {
            if (b.lost(w)) {
                return LinkerState.LOST
            }
            if (b.lost(!w)) {
                return LinkerState.WON
            }
            return LinkerState.DEFINED
        }
    }
}