package org.example

import java.util.concurrent.ConcurrentHashMap

class Turn(val key:Int, val hash:Long, val weight:Double){
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

open class BoardLinker(val b:Board, val w:Boolean, val boardWeight:Int, val state:LinkerState
) {
    var tickAccumulated:Int = 0//ticks accumulated currently
    var tickProcessed:Long = 0L//total count of ticks consumed
    fun setTurns(t:Array<Turn>, minOpponentBoardWeight:Int):BoardLinkerLinked {
        return BoardLinkerLinked(b,w,boardWeight,t,boardWeight.toDouble()-minOpponentBoardWeight)
    }
    //propagation
    fun propagateTicks(tickCount:Int, bs:BoardsStorage) {
        tickAccumulated += tickCount
        processTicks(bs)
    }
    open fun processTicks(bs:BoardsStorage) {
        //TODO: return ticks for won/lost
        if (state == LinkerState.DEFINED && tickAccumulated >= LINK_COST) {
            tickAccumulated -= LINK_COST
            tickProcessed += LINK_COST
            developMoves(bs)
        }
    }
    fun developMoves(bs:BoardsStorage):Pair<Double,Long> {
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
            bs.updateLinker(BoardLinker(b,w,boardWeight,LinkerState.WON))
            return Pair(WIN_WEIGHT.toDouble(), out.size.toLong())
        } else {
            bs.updateLinker(setTurns(out.toTypedArray(), min))
        }
        return Pair(boardWeight.toDouble()-min, out.size.toLong())
    }
    open fun getFlatWeight(ts:Long, bs:BoardsStorage):Pair<Double,Long> {
        if (state == LinkerState.LOST) {
            return Pair(LOST_WEIGHT.toDouble(),1)
        }
        if (state == LinkerState.WON) {
            return Pair(WIN_WEIGHT.toDouble(),1)
        }
        //should never be here, but if here...
        //    we have to link the board to get a value
        return developMoves(bs)
    }
    companion object {
        const val LINK_COST = 100
        const val TURNS_MULT = 10
        const val WIN_WEIGHT = 10000
        const val LOST_WEIGHT = 1
    }
}
class BoardLinkerLinked(b:Board, w:Boolean, boardWeight:Int, val turns:Array<Turn>,
    //stats
    //it is dangerous to use vars here... it's done only for the demo purposes
    var extValue:Double = 1.0,//board score avaraged by total amount of children existed
    var weightTimestamp:Long = 0L,//timestamp of a last count
    var childCount:Long = 0L,//timestamp of a last count
):BoardLinker(b, w, boardWeight, LinkerState.LINKED) {
    override fun processTicks(bs:BoardsStorage) {
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
    fun setWeight(w:Double, c: Long, ts:Long) {
        if (w.isNaN()) {
            println(w)
        }
        extValue = w
        childCount = c
        weightTimestamp = ts
    }
    override fun getFlatWeight(ts:Long, bs:BoardsStorage):Pair<Double,Long> {
        return Pair(extValue,turns.size.toLong())
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
            val out = BoardLinker(b,w,wght,bs)
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

        fun tempAvg(bl: BoardLinkerLinked):Double {
            val weights = mutableListOf<Pair<Double,Long>>()
            for (t in bl.turns) {
                storage.loadSafe(t.hash) { bbl ->
                    weights.add(Pair(bbl.boardWeight.toDouble(),0))
                }
            }
            val (sum,count) = fastAvg(bl.boardWeight, weights)
            return sum
        }

        fun avgNextTurn(visited:Array<Long>, bl: BoardLinker, ts:Long):Pair<Double,Long> {
            callCount++
            if (bl is BoardLinkerLinked) {
                if (bl.tickProcessed < TICK_THRESHOLD) {
                    val fw = bl.getFlatWeight(ts, this)
                    //normalizeTurnWeights(bl)
                    //val (sum,count) = fastAvg(bl.boardWeight, weights)
                    bl.setWeight(fw.first, fw.second, ts)
                    return fw
                } else {
                    var childCount = 0L
                    val weights = mutableListOf<Triple<Double,Long,Int>>()
                    bl.iterateTurns(ts, this) { (t,bbl) ->
                        if (visited.contains(t.hash)) {
                            val fw = bbl.getFlatWeight(ts, this)
                            childCount += fw.second
                            weights.add(addBoardWeight(fw, bbl))
                        } else {
                            val fw = avgNextTurn(visited.plus(t.hash), bbl, ts)
                            childCount += fw.second
                            weights.add(addBoardWeight(fw, bbl))
                        }
                    }
                    //normalizeTurnWeights(bl)
                    val (wght,count) = sumWghtAvg(bl.boardWeight,weights)
                    bl.setWeight(wght, count+bl.turns.size, ts)
                    return Pair(wght, count+bl.turns.size)
                }
            }
            return bl.getFlatWeight(ts, this)
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


    fun addBoardWeight(p:Pair<Double,Long>, bl:BoardLinker):Triple<Double,Long,Int> {
        return Triple(p.first,p.second,bl.boardWeight)
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
            if (bl is BoardLinkerLinked) {
                for (t in bl.turns) {
                    storage.loadSafe(t.hash) { bbl ->
                        if (bbl is BoardLinkerLinked) {
                            if (bbl.extValue < min) {
                                min = bbl.extValue
                                board = bbl.b
                                turn = t.key
                            }
                        }
                    }
                }
            }
        }
        return Pair(board,turn)
    }
    fun printWeights(b: Board, w: Boolean) {
        getLinker(b,w) { bl ->
            if (bl is BoardLinkerLinked) {
                println(" [${bl.extValue} ${bl.childCount}] ")
                for (t in bl.turns) {
                    storage.loadSafe(t.hash) { bbl ->
                        if (bbl is BoardLinkerLinked) {
                            print(" (${t.weight} ${bbl.extValue} ${bbl.childCount} ${bbl.tickProcessed}) ")
                            Turn.printKey(bbl.b, t.key)
                            bbl.b.print()
                        }
                    }
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
            if (bl is BoardLinkerLinked) {
                for (t in bl.turns) {
                    storage.loadSafe(t.hash) { bbl ->
                        if (bbl is BoardLinkerLinked) {
                            print(String.format(" %.4f|%.4f", t.weight, bbl.extValue))
                        }
                    }
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