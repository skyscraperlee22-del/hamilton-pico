package com.hamilton.stage.performance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All positions are in the original stage's metre coordinate system. */
data class StagePoint(val x: Float, val y: Float, val z: Float) {
    fun distance(other: StagePoint): Float = kotlin.math.sqrt((x-other.x)*(x-other.x)+(y-other.y)*(y-other.y)+(z-other.z)*(z-other.z))
    fun lerp(other: StagePoint, t: Float) = StagePoint(x+(other.x-x)*t,y+(other.y-y)*t,z+(other.z-z)*t)
}
data class LyricCue(val start: Float, val end: Float, val en: String, val zh: String)
data class SongExcerpt(val id: String, val song: String, val file: String, val duration: Float, val singers: List<String>, val cues: List<LyricCue>)
data class CastMember(val role: String, val name: String, val english: String, val sentences: List<String>, val asset: String, val position: StagePoint, val facing: Float, val min: StagePoint, val max: StagePoint, val music: SongExcerpt)
data class StageYear(val year: Int, val title: String, val available: Boolean)
data class StageScene(val year: Int, val actors: List<CastMember>)
data class StageCatalog(val years: List<StageYear>, val scenes: List<StageScene>) {
    val models: List<CastMember> get() = scenes.flatMap { it.actors }.distinctBy { it.role }
    fun scene(index: Int) = scenes[index]
    companion object {
        fun load(context: Context): StageCatalog {
            val json = JSONObject(context.assets.open("stage-catalog.json").bufferedReader().use { it.readText() })
            val years = json.getJSONArray("years").objects().map { StageYear(it.getInt("year"), it.getString("title"), it.getBoolean("available")) }
            val scenes = json.getJSONArray("scenes").objects().map { scene ->
            val actors = scene.getJSONArray("actors").objects().map { actor ->
                val music = actor.getJSONObject("music")
                val bounds = actor.getJSONObject("bounds")
                CastMember(actor.getString("role"), actor.getString("name"), actor.getString("english"), actor.getJSONArray("sentences").strings(), actor.getString("asset"), actor.getJSONArray("position").point(), actor.getDouble("facing").toFloat(), bounds.getJSONArray("min").point(), bounds.getJSONArray("max").point(),
                    SongExcerpt(music.getString("id"),music.getString("song"),music.getString("file"),music.getDouble("duration").toFloat(),music.getJSONArray("singers").strings(),music.getJSONArray("cues").objects().map { LyricCue(it.getDouble("start").toFloat(),it.getDouble("end").toFloat(),it.getString("en"),it.getString("zh")) }))
            }
            check(actors.any { it.role == "Hamilton" } && actors.map { it.role }.distinct().size == actors.size)
            check(actors.all { it.music.cues.isNotEmpty() && it.music.singers.all { role -> actors.any { actor -> actor.role == role } } })
            StageScene(scene.getInt("year"), actors)
            }
            check(years.map { it.year } == scenes.map { it.year } && scenes.size == 6)
            return StageCatalog(years, scenes)
        }
    }
}
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun JSONArray.point() = StagePoint(getDouble(0).toFloat(),getDouble(1).toFloat(),getDouble(2).toFloat())
