package org.example

import java.util.concurrent.ConcurrentHashMap

class Turn(val key:Int, val hash:Long,
                //it is dangerous to use vars here... it's done only for the demo purposes
                var weight:Double = 1.0, var state:LinkerState = LinkerState.DEFINED){
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
        return state == LinkerState.DEFINED || state == LinkerState.LINKED
    }
}

interface BoardsStorage {
    fun defineBoard(b:Board, w:Boolean)
    fun updateLinker(bl:BoardLinker)
    fun propagateTicks(tickCount:Int, board_hash:Long)
    fun time():Long
}

enum class LinkerState {DEFINED,WON,LOST,LINKED}

class BoardLinker(val b:Board, val w:Boolean, val turns:Array<Turn>,
                  //stats
                  //it is dangerous to use vars here... it's done only for the demo purposes
                  var state:LinkerState = LinkerState.DEFINED,
                  var avgWeight:Double = 1.0,//board score avaraged by total amount of children existed
                  var tickAccumulated:Int = 0,//ticks accumulated currently
                  var tickProcessed:Long = 0L,//total count of ticks consumed
                  var childCount:Long = 0L,//total number of boards derived from current
                  var weightTimestamp:Long = 0L,//timestamp of a last count
                  var boardWeight:Int = 0
) {
    //propagation
    fun propagateTicks(tickCount:Int, bs:BoardsStorage) {
        tickAccumulated += tickCount
        when(state) {
            LinkerState.DEFINED -> processDefined(bs)
            LinkerState.LINKED -> processLinked(bs)
            else -> nothing()
        }
    }
    fun nothing() {}
    fun processDefined(bs:BoardsStorage) {
        if (tickAccumulated >= LINK_COST) {
            tickAccumulated -= LINK_COST
            tickProcessed += LINK_COST
            val out = mutableListOf<Turn>()
            b.develop(w) { (kk,bb) ->
                bs.defineBoard(bb,!w)
                out.add(Turn(kk,bb.calcHash(!w)))
            }
            bs.updateLinker(setTurns(out.toTypedArray()))
        }
    }
    fun processLinked(bs:BoardsStorage) {
        if (tickAccumulated >= turns.size*TURNS_MULT) {
            var budget = 0.0
            for (t in turns) {
                if (t.isNotDone()) {
                    budget += t.weight
                }
            }
            val b_chunk = tickAccumulated/budget
            for (t in turns) {
                if (t.isNotDone()) {
                    val sz = (b_chunk*t.weight).toInt()
                    tickAccumulated -= sz
                    tickProcessed += sz
                    bs.propagateTicks(sz, t.hash)
                }
            }
        }
    }
    fun setTurns(t:Array<Turn>):BoardLinker {
        return BoardLinker(b,w,t,LinkerState.LINKED,
            avgWeight,tickAccumulated,tickProcessed,childCount,weightTimestamp,boardWeight)
    }
    fun setWeight(w:Double, c: Long, ts:Long) {
        if (w.isNaN()) {
            println(w)
        }
        avgWeight = w
        childCount = c
        weightTimestamp = ts
    }
    fun getWeight():Pair<Double,Long> {
        return Pair(avgWeight,childCount)
    }
    companion object {
        const val LINK_COST = 100
        const val TURNS_MULT = 10
        const val WIN_WEIGHT = 1000.0
        const val LOST_WEIGHT = -1000.0
        const val DEFAULT_WEIGHT = 1.0
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
            //evaluate lost here might be bad idea, add win as well??
            val out = if (b.lost(w)) BoardLinker(b,w, arrayOf(), LinkerState.LOST, BoardLinker.LOST_WEIGHT)
                                else BoardLinker(b,w, arrayOf())
            if (out.state != LinkerState.LOST) {
                out.boardWeight = out.b.evalBoard(out.w)
                out.avgWeight = (out.boardWeight - out.b.evalBoard(!out.w)).toDouble()
                if (out.avgWeight.isNaN()) {
                    println(out.avgWeight)
                }
            }
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

    fun tempAvg(bl: BoardLinker):Double {
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
        if (bl.weightTimestamp == ts) {
            return bl.getWeight()
        }
        if (bl.state == LinkerState.LOST) {
            bl.setWeight(BoardLinker.LOST_WEIGHT, 0, ts)
            return bl.getWeight()
        }
        if (bl.state == LinkerState.WON) {
            bl.setWeight(BoardLinker.WIN_WEIGHT, 0, ts)
            return bl.getWeight()
        }
        if (bl.state == LinkerState.DEFINED) {
            bl.setWeight(bl.b.evalBoardAvg(bl.w).toDouble(), 0, ts)
            return bl.getWeight()
        }
        //var win = false
        if (bl.state == LinkerState.LINKED) {
            if (bl.tickProcessed < TICK_THRESHOLD) {
                val weights = mutableListOf<Pair<Double,Long>>()
                for (t in bl.turns) {
                    storage.loadSafe(t.hash) { bbl ->
                        weights.add(Pair(bbl.boardWeight.toDouble(),0))
                        t.weight = bbl.boardWeight.toDouble()
                        t.state = bbl.state
                    }
                }
                normalizeTurnWeights(bl)
                val (sum,count) = fastAvg(bl.boardWeight, weights)
                bl.setWeight(sum, count+weights.size, ts)
            } else {
                val weights = mutableListOf<Triple<Double,Long,Int>>()
                for (t in bl.turns) {
                    if (!visited.contains(t.hash)) {
                        storage.loadSafe(t.hash) { bbl ->
                            val a = avgNextTurn(visited.plus(t.hash), bbl, ts)
                            t.weight = a.first
                            t.state = bbl.state
                            weights.add(addBoardWeight(a,bbl))
                        }
                    } else {
                        storage.loadSafe(t.hash) { bbl ->
                            t.state = bbl.state
                            if (bbl.weightTimestamp != ts) {
                                bbl.setWeight(tempAvg(bbl),0,ts)
                            }
                            t.weight = bbl.avgWeight
                            weights.add(addBoardWeight(Pair(t.weight,bbl.childCount),bbl))
                        }
                    }
                }
                normalizeTurnWeights(bl)
                val (wght,count) = sumWghtAvg(bl.boardWeight,weights)
                bl.setWeight(wght, count+bl.turns.size, ts)
            }
        }
        if (bl.state == LinkerState.WON) {
            bl.setWeight(BoardLinker.WIN_WEIGHT, 0, ts)
        }
        return bl.getWeight()
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

    fun normalizeTurnWeights(bl:BoardLinker) {
        //check if win, inverse weights, adjust zero, normalize
        var sum = 0.0
        var min = 1000000.0
        for (t in bl.turns) {
            if (t.state == LinkerState.LOST) {
                bl.state = LinkerState.WON
            }
            t.weight = -t.weight
            if (t.weight < min) {
                min = t.weight
            }
            sum += t.weight
        }
        sum = sum/bl.turns.size - min //adj zero and normalize
        for (t in bl.turns) {
            t.weight = (t.weight-min)/avoidNullSum(sum) + WEIGHT_BIAS
        }
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

    override fun defineBoard(b: Board, w: Boolean) {
        getLinker(b,w) { bb ->
            //done
        }
    }

    override fun updateLinker(bl: BoardLinker) {
        storage.store(bl.b.calcHash(bl.w), bl)
    }

    override fun propagateTicks(tickCount: Int, board_hash: Long) {
        storage.loadSafe(board_hash) { bl ->
            bl.propagateTicks(tickCount, this)
        }
    }

    override fun time(): Long {
        return System.currentTimeMillis()
    }
    fun turn(b: Board, w: Boolean):Pair<Board,Int> {
        var min = 1000000000.0
        var board = b
        var turn = 0
        getLinker(b,w) { bl ->
            for (t in bl.turns) {
                storage.loadSafe(t.hash) { bbl ->
                    if (bbl.avgWeight < min) {
                        min = bbl.avgWeight
                        board = bbl.b
                        turn = t.key
                    }
                }
            }
        }
        return Pair(board,turn)
    }
    fun printWeights(b: Board, w: Boolean) {
        getLinker(b,w) { bl ->
            println(" [${bl.avgWeight} ${bl.childCount}] ")
            for (t in bl.turns) {
                storage.loadSafe(t.hash) { bbl ->
                    print(" (${t.weight} ${bbl.b.evalBoardAvg(bbl.w)} ${bbl.avgWeight} ${bbl.childCount} ${bbl.tickProcessed}) ")
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
                    print(String.format(" %.4f|%.4f",t.weight,bbl.avgWeight))
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
    }
}