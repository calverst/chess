package org.example

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import java.util.*
import kotlin.experimental.and

typealias Acceptor<T> = (T) -> Unit

interface GenericStorage<K, V> {
    fun load(key: K, cb: Acceptor<V>, cbk: Acceptor<K>)
    fun store(key: K, value: V)
    fun loadSafe(key: K, cb: Acceptor<V>) {
        load(key, cb) { }
    }
}

abstract class Piece(val x: Byte, val y: Byte, val w: Boolean) {
    abstract fun code(): String
    abstract fun ind(): Int
    abstract fun wht(): Int
    abstract fun move(to:Piece):Piece
    abstract fun moves(b:Board, cb:Acceptor<Piece>)
    fun inBoardBounds(x: Int, y:Int): Boolean {
        return (x >= 0) && (x <= 7) && (y >= 0) && (y <= 7)
    }
    fun dirMove(b:Board, x: Int, y:Int, dx: Int, dy:Int, cb:Acceptor<Piece>) {
        if (inBoardBounds(x,y)) {
            b.get(x,y) { p1 ->
                cb(p1)
                if (p1 is EmptySq) {
                    dirMove(b, x + dx, y + dy, dx, dy, cb)
                }
            }
        }
    }
    fun singleMove(b:Board, dx: Int, dy:Int, cb:Acceptor<Piece>) {
        b.get(x.toInt()+dx,y.toInt()+dy,cb)
    }
    fun boardColor():Boolean {
        return (x+y)%2==0
    }
}

class EmptySq(x: Byte, y: Byte) : Piece(x, y, (x+y)%2 == 0) {
    override fun code(): String {
        return " "
    }

    override fun ind(): Int {
        return 0
    }

    override fun move(to: Piece):Piece {
        return EmptySq(to.x,to.y)
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
    }

    override fun wht(): Int {
        return 0
    }
}

class Pawn(x: Byte, y: Byte, w: Boolean) : Piece(x, y, w) {
    override fun code(): String {
        return if (w) "P" else "p"
    }
    override fun ind(): Int {
        return 1
    }
    override fun wht(): Int {
        return 30
    }
    override fun move(to: Piece):Piece {
        return Pawn(to.x,to.y,w)
    }
    fun canGo2():Boolean {
        return if (w) y == Y6 else y == Y1
    }
    fun canPromote():Boolean {
        return if (w) y == Y1 else y == Y6
    }
    fun canPass():Boolean {
        return if (w) y == Y3 else y == Y4
    }
    fun passing(m:Piece):Boolean {
        return canPass() && m is EmptySq && (x != m.x)
    }
    fun go(d:Int):Int {
        return if (w) y-d else y+d
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
        b.get(x.toInt(),go(D1)) { p1 ->
            if (p1 is EmptySq) {
                cb(p1)
                if (canGo2()) {
                    b.get(x.toInt(), go(D2)) { p2 ->
                        if (p2 is EmptySq) {
                            cb(p2)
                        }
                    }
                }
            }
        }
        val goPawn = fun(p1: Piece) {
            if (p1 is EmptySq) {
                //passing
                if (canPass()) {
                    b.get(p1.x.toInt(), y.toInt()) { p2 ->
                        if (p2.w != w && p2 is Pawn) {
                            cb(p1)
                        }
                    }
                }
            } else {
                cb(p1)
            }
        }

        b.get(x.toInt()+1,go(D1), goPawn)
        b.get(x.toInt()-1,go(D1), goPawn)
        //promotion is part of the development logic
    }

    companion object {
        const val Y6 = 6.toByte()
        const val Y1 = 1.toByte()
        const val Y4 = 4.toByte()
        const val Y3 = 3.toByte()
        const val D2 = 2
        const val D1 = 1
    }
}

class Rook(x: Byte, y: Byte, w: Boolean) : Piece(x, y, w) {
    override fun code(): String {
        return if (w) "R" else "r"
    }
    override fun ind(): Int {
        return 2
    }
    override fun wht(): Int {
        return 100
    }
    override fun move(to: Piece):Piece {
        return Rook(to.x,to.y,w)
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
        dirMove(b,x.toInt()+1,y.toInt(),1,0,cb)
        dirMove(b,x.toInt()-1,y.toInt(),-1,0,cb)
        dirMove(b,x.toInt(),y.toInt()+1,0,1,cb)
        dirMove(b,x.toInt(),y.toInt()-1,0,-1,cb)
        //castles is part of the board development logic
    }

    fun canCastle():Boolean {
        return if (w) ((x==0.toByte() || x==7.toByte()) && y==7.toByte())
        else ((x==0.toByte() || x==7.toByte()) && y==0.toByte())
    }
}

class Bishop(x: Byte, y: Byte, w: Boolean) : Piece(x, y, w) {
    override fun code(): String {
        return if (w) "B" else "b"
    }
    override fun ind(): Int {
        return 3
    }
    override fun wht(): Int {
        return 50
    }
    override fun move(to: Piece):Piece {
        return Bishop(to.x,to.y,w)
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
        dirMove(b,x.toInt()+1,y.toInt()+1,1,1,cb)
        dirMove(b,x.toInt()-1,y.toInt()-1,-1,-1,cb)
        dirMove(b,x.toInt()-1,y.toInt()+1,-1,1,cb)
        dirMove(b,x.toInt()+1,y.toInt()-1,1,-1,cb)
    }

}

class Knight(x: Byte, y: Byte, w: Boolean) : Piece(x, y, w) {
    override fun code(): String {
        return if (w) "N" else "n"
    }
    override fun ind(): Int {
        return 4
    }
    override fun wht(): Int {
        return 50
    }

    override fun move(to: Piece):Piece {
        return Knight(to.x,to.y,w)
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
        singleMove(b, 1,2, cb)
        singleMove(b, 1,-2, cb)
        singleMove(b, -1,2, cb)
        singleMove(b, -1,-2, cb)
        singleMove(b, 2,1, cb)
        singleMove(b, 2,-1, cb)
        singleMove(b, -2,1, cb)
        singleMove(b, -2,-1, cb)
    }

}

class King(x: Byte, y: Byte, w: Boolean) : Piece(x, y, w) {
    override fun code(): String {
        return if (w) "K" else "k"
    }
    override fun ind(): Int {
        return 5
    }
    override fun wht(): Int {
        return 100
    }

    override fun move(to: Piece):Piece {
        return King(to.x,to.y,w)
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
        singleMove(b, 1,1, cb)
        singleMove(b, 1,-1, cb)
        singleMove(b, -1,1, cb)
        singleMove(b, -1,-1, cb)
        singleMove(b, 1,0, cb)
        singleMove(b, -1,0, cb)
        singleMove(b, 0,1, cb)
        singleMove(b, 0,-1, cb)
    }

    fun canCastle():Boolean {
        return if (w) (x==4.toByte() && y==7.toByte())
                else (x==4.toByte() && y==0.toByte())
    }

}

class Queen(x: Byte, y: Byte, w: Boolean) : Piece(x, y, w) {
    override fun code(): String {
        return if (w) "Q" else "q"
    }
    override fun ind(): Int {
        return 6
    }
    override fun wht(): Int {
        return 200
    }

    override fun move(to: Piece):Piece {
        return Queen(to.x,to.y,w)
    }

    override fun moves(b:Board, cb:Acceptor<Piece>) {
        dirMove(b,x.toInt()+1,y.toInt()+1,1,1,cb)
        dirMove(b,x.toInt()-1,y.toInt()-1,-1,-1,cb)
        dirMove(b,x.toInt()-1,y.toInt()+1,-1,1,cb)
        dirMove(b,x.toInt()+1,y.toInt()-1,1,-1,cb)
        dirMove(b,x.toInt()+1,y.toInt(),1,0,cb)
        dirMove(b,x.toInt()-1,y.toInt(),-1,0,cb)
        dirMove(b,x.toInt(),y.toInt()+1,0,1,cb)
        dirMove(b,x.toInt(),y.toInt()-1,0,-1,cb)
    }

}


class Board(val b: Array<Array<Piece>>) {
    fun get(x:Int, y:Int, cb:Acceptor<Piece>) {
        if (y>=0 && y<b.size) {
            val bb = b[y]
            if (x>=0 && x<bb.size) {
                cb(bb[x])
            }
        }
    }
    fun setToPiece(to:Piece):Board {
        val out = b.copyOf()
        out[to.y.toInt()] = out[to.y.toInt()].copyOf()
        out[to.y.toInt()][to.x.toInt()] = to
        return Board(out)
    }
    fun move(from:Piece, to:Piece):Board {
        val out = b.copyOf()
        out[from.y.toInt()] = out[from.y.toInt()].copyOf()
        out[from.y.toInt()][from.x.toInt()] = EmptySq(from.x, from.y)
        out[to.y.toInt()] = out[to.y.toInt()].copyOf()
        out[to.y.toInt()][to.x.toInt()] = from.move(to)
        return Board(out)
    }
    fun eachPiece(w:Boolean, cb:Acceptor<Piece>) {
        for (a in b) {
            for (aa in a) {
                if ((!(aa is EmptySq)) && (aa.w == w)) {
                    cb(aa)
                }
            }
        }
    }
    fun canCastle(rook:Rook, king:King):Boolean {
        return rook.w == king.w && rook.canCastle() && king.canCastle()
    }
    fun developDefault(aa:Piece, m:Piece, cb:Acceptor<Pair<Int,Board>>) {
        if (m is EmptySq) {
            cb(Pair(encodeTurnKey(aa,m),move(aa, m)))
        } else {
            if (m.w != aa.w) {
                cb(Pair(encodeTurnKey(aa,m),move(aa, m)))
            }
        }
    }
    fun develop(w:Boolean, cb:Acceptor<Pair<Int,Board>>) {//made each possible move for w side
        eachPiece(w) { aa ->
            aa.moves(this) { m ->
                if (aa is Rook && m is King && canCastle(aa,m)) {
                    if (aa.x < m.x) {
                        cb(Pair(encodeTurnKey(aa,m),move(aa,EmptySq(B3, aa.y)).move(m,EmptySq(B2, m.y))))
                    } else {
                        cb(Pair(encodeTurnKey(aa,m),move(aa,EmptySq(B5, aa.y)).move(m,EmptySq(B6, m.y))))
                    }
                }
                if (aa is Pawn) {
                    if (aa.canPromote()) {
                        developDefault(aa,m) { (kk,bb) ->
                            cb(Pair(kk,bb.setToPiece(Queen(m.x,m.y,aa.w))))
                            cb(Pair(encodeTurnKey(aa,m,true),bb.setToPiece(Knight(m.x,m.y,aa.w))))
                        }
                    } else if (aa.passing(m)) {
                        developDefault(aa,m) { (kk,bb) ->
                            cb(Pair(kk,bb.setToPiece(EmptySq(m.x,aa.y))))
                        }
                    } else {
                        developDefault(aa,m,cb)
                    }
                } else {
                    developDefault(aa,m,cb)
                }
            }
        }
    }
    fun evalBoardAvg(w:Boolean):Int {
        return evalBoard(w)-evalBoard(!w)
    }
    fun evalBoard(w:Boolean):Int {
        //println("eval for ${w}")
        var total = 0
        eachPiece(w) { aa ->
            var d = 0
            var c = 0
            aa.moves(this) { m ->
                //print("m(${m.code()},${m.x},${m.y}) ")
                c++
                if (m is EmptySq) {
                    //might change move weight for each piece kind
                    d += protectWeights[aa.ind()][m.ind()]
                } else {
                    if (m.w == w) {//same color
                        d += protectWeights[aa.ind()][m.ind()]
                    } else {
                        d += takesWeights[aa.ind()][m.ind()]
                    }
                }
            }
            //println("eval for ${aa.code()}(${aa.x},${aa.y}) ${c} ${d}")
            total += aa.wht()
            total += d
        }
        eachPiece(!w) { aa ->
            total -= aa.wht()
        }
        return total
    }
    fun print() {
        for (a in b) {
            print("[")
            for (aa in a) {
                print(aa.code())
            }
            println("]")
        }
    }
    fun lost(w:Boolean): Boolean {
        var lost = true
        findKing(w) { k ->
            lost = false
        }
        return lost
    }
    fun findKing(w:Boolean, cb:Acceptor<King>) {
        //find a king of w
        for (a in b) {
            for (aa in a) {
                if (aa.w == w && aa is King) {
                    return cb(aa)
                }
            }
        }
    }
    fun calcHash(w:Boolean):Long {
        var hash = if (w) 1L else 0L
        for (a in b) {
            for (aa in a) {
                if (aa.w) {
                    hash = 31 * hash + aa.ind()
                } else {
                    hash = 31 * hash + aa.ind() * 2
                }
            }
        }
        return hash
    }
    companion object {
        const val B2 = 2.toByte()
        const val B3 = 3.toByte()
        const val B5 = 5.toByte()
        const val B6 = 6.toByte()
        const val mask3b = 7.toByte()
        fun encodeTurnKey(from: Piece, to:Piece, isPromoteKnight: Boolean = false):Int {
            //add more params if needed (e.g. promote to knight), t must be unique
            val pk = if (isPromoteKnight) 1.shl(10) else 0
            return from.x.and(mask3b).toInt().shl(9) + from.y.and(mask3b).toInt().shl(6) +
                    to.x.and(mask3b).toInt().shl(3) + to.y.and(mask3b).toInt() + pk
        }
        val EMSQ = EmptySq(0,0)
        //asses = current board value - best next move value (not average)
        val takesWeights = arrayOf(
            arrayOf(0,0,0,0,0,0,0), //Empty   0
            arrayOf(10,50,80,100,100,200,100), //Pawn    1
            arrayOf(10,20,50,30,30,200,100), //Rook    5
            arrayOf(10,30,80,40,40,200,100), //Bishop  3
            arrayOf(10,30,80,40,40,200,100), //Knight  3
            arrayOf(10,30,30,30,30,30,30), //King    10
            arrayOf(10,20,50,30,30,200,100)) //Queen   20
        val protectWeights = arrayOf(
            arrayOf(0,0,0,0,0,0,0), //Empty
            arrayOf(10,30,20,20,20,0,20), //Pawn
            arrayOf(10,30,20,30,30,0,20), //Rook
            arrayOf(10,30,20,30,30,0,20), //Bishop
            arrayOf(10,30,20,30,30,0,20), //Knight
            arrayOf(10,30,30,30,30,0,20), //King
            arrayOf(10,30,30,30,30,0,20)) //Queen
    }
}

class BoardSerializer : JsonSerializer<Board>() {
    override fun serialize(b: Board, jgen: JsonGenerator, provider: SerializerProvider) {
        jgen.writeStartArray()
        for (v in b.b) {
            val out = StringBuilder()
            for (vv in v) {
                out.append(vv.code())
            }
            jgen.writeString(out.toString())
        }
        jgen.writeEndArray()
    }
    override fun handledType(): Class<Board> {
        return Board::class.java
    }
}

class BoardDeserializer : JsonDeserializer<Board>() {
    override fun deserialize(p: JsonParser, ctx: DeserializationContext): Board {
        val node: JsonNode = p.codec.readTree(p)
        if (node.isArray) {
            val out = mutableListOf<Array<Piece>>()
            for ((idy, vv) in node.withIndex()) {
                if (idy > 7) {
                    throw JsonParseException("More than 8 lines was found")
                }
                val oout = mutableListOf<Piece>()
                if (vv.isTextual) {
                    val s = vv.asText()
                    if (s.length != 8) {
                        throw JsonParseException("String of length 8 expected")
                    }
                    for ((idx, char) in s.withIndex()) {
                        val pp = when (char) {
                            ' ' -> EmptySq(idx.toByte(), idy.toByte())
                            'P' -> Pawn(idx.toByte(), idy.toByte(), true)
                            'R' -> Rook(idx.toByte(), idy.toByte(), true)
                            'B' -> Bishop(idx.toByte(), idy.toByte(), true)
                            'N' -> Knight(idx.toByte(), idy.toByte(), true)
                            'K' -> King(idx.toByte(), idy.toByte(), true)
                            'Q' -> Queen(idx.toByte(), idy.toByte(), true)
                            'p' -> Pawn(idx.toByte(), idy.toByte(), false)
                            'r' -> Rook(idx.toByte(), idy.toByte(), false)
                            'b' -> Bishop(idx.toByte(), idy.toByte(), false)
                            'n' -> Knight(idx.toByte(), idy.toByte(), false)
                            'k' -> King(idx.toByte(), idy.toByte(), false)
                            'q' -> Queen(idx.toByte(), idy.toByte(), false)
                            else -> throw JsonParseException("Unexpected piece ${char}")
                        }
                        oout.add(pp)
                    }
                } else {
                    throw JsonParseException("String expected")
                }
                out.add(oout.toTypedArray())
            }
            return Board(out.toTypedArray())
        }
        throw JsonParseException("Array expected")
    }
}