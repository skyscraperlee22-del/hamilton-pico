package com.hamilton.stage.performance

import java.util.PriorityQueue
import kotlin.math.*

/** Navigation follows the real stairs; floor routes avoid the cast and downstage furniture. */
class StageNavigation(private val cast: List<CastMember>) {
    private val stairFoot = StagePoint(6.8f,.04f,5.2f)
    private val stairTop = StagePoint(6.8f,2.8f,.40f)
    private val galleryCorner = StagePoint(6.8f,2.8f,-4.8f)
    val home = StagePoint(0f,.04f,2.6f)
    private val floorCast = cast.filter { it.role != "Hamilton" && it.position.y < 1f }
    fun route(from: StagePoint, role: String): List<StagePoint> {
        val result = mutableListOf<StagePoint>()
        var floorStart = from
        val upstairs = from.y > .09f
        if (role == "GeorgeIII") {
            if (upstairs) {
                // Retargeting while on the stair stays on its centreline.
                if (from.x > 5.9f && from.z > .45f) result += stairTop
            } else {
                result += floorRoute(from,stairFoot)
                result += stairTop
            }
            if (from.z > -3.9f || !upstairs) result += galleryCorner
            result += StagePoint(4.4f,2.8f,-4.8f)
            return result
        }
        if (upstairs) {
            if (from.z < .4f) {
                result += galleryCorner
                result += stairTop
            }
            result += stairFoot
            floorStart = stairFoot
        }
        val targets = if (role == "Hamilton") listOf(home) else approaches(cast.first { it.role == role }.position)
        // A clear destination may sit in a pocket surrounded by other performers. Use the
        // first reachable side position, rather than committing before the path search.
        val floorPath = targets.firstNotNullOfOrNull { target -> runCatching { floorRoute(floorStart,target) }.getOrNull() }
            ?: error("No reachable approach to $role")
        result += floorPath
        return result.filterIndexed { i, p -> p.distance(if(i==0) from else result[i-1]) > .015f }
    }
    private fun approaches(p: StagePoint): List<StagePoint> {
        val candidates = listOf(1.35f to .3f,-1.35f to .3f,1f to 1.1f,-1f to 1.1f,0f to 1.5f,0f to -1.5f)
            .map { StagePoint(p.x+it.first,.04f,p.z+it.second) }
        return candidates.filter(::clear)
    }
    private fun clear(p: StagePoint): Boolean {
        if (p.x !in -5.8f..7.15f || p.z !in -3.9f..5.7f) return false
        // Props beneath the left balcony; keep the approach corridor open.
        if (p.x in -5.25f..-2.9f && p.z < -1.6f) return false
        return floorCast.none { hypot(p.x-it.position.x,p.z-it.position.z) < 1.08f }
    }
    private fun lineClear(a: StagePoint,b: StagePoint): Boolean {
        val samples=max(1,ceil(a.distance(b)/.12f).toInt())
        return (1..samples).all { clear(a.lerp(b,it.toFloat()/samples)) }
    }
    private data class Cell(val x: Int,val z: Int)
    private fun point(c: Cell)=StagePoint(c.x*.25f,.04f,c.z*.25f)
    private fun cell(p: StagePoint)=Cell((p.x/.25f).roundToInt(),(p.z/.25f).roundToInt())
    private data class Open(val cell:Cell,val score:Float)
    private fun floorRoute(a:StagePoint,b:StagePoint):List<StagePoint> {
        if (a.distance(b)<.02f) return emptyList()
        if (lineClear(a,b)) return listOf(b)
        val start=cell(a);val end=cell(b)
        val open=PriorityQueue<Open>(compareBy { it.score });open.add(Open(start,0f))
        val g=mutableMapOf(start to 0f);val prev=mutableMapOf<Cell,Cell>();val closed=mutableSetOf<Cell>()
        var found:Cell?=null
        while(open.isNotEmpty()) {
            val at=open.remove().cell
            if(!closed.add(at)) continue
            if((at==end || point(at).distance(b)<.45f) && lineClear(point(at),b)) { found=at;break }
            for(dx in -1..1) for(dz in -1..1) {
                if(dx==0&&dz==0) continue
                val next=Cell(at.x+dx,at.z+dz)
                if(!clear(point(next)) || !lineClear(point(at),point(next))) continue
                val cost=g.getValue(at)+hypot(dx.toFloat(),dz.toFloat())
                if(cost<(g[next]?:Float.POSITIVE_INFINITY)) {
                    g[next]=cost;prev[next]=at;open.add(Open(next,cost+point(next).distance(b)/.25f))
                }
            }
        }
        check(found!=null) { "No safe floor route from $a to $b" }
        val raw=mutableListOf(b);var n=found!!
        while(n!=start) {raw.add(point(n));n=prev.getValue(n)}
        raw.reverse()
        val smooth=mutableListOf<StagePoint>();var anchor=a;var i=0
        while(i<raw.size) {
            var far=i
            while(far+1<raw.size&&lineClear(anchor,raw[far+1]))far++
            smooth+=raw[far];anchor=raw[far];i=far+1
        }
        return smooth
    }
}
