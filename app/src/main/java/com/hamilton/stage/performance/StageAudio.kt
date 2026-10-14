package com.hamilton.stage.performance

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/** Local, cancellation-safe excerpt playback. Lyrics use the decoder's actual playhead. */
class StageAudio(private val context:Context) {
    private val manager=context.getSystemService(AudioManager::class.java)
    private var player:MediaPlayer?=null
    private var generation=0
    private var prepared=false
    var playing=false; private set
    var onInterrupted:()->Unit={}
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { value ->
            if(value==AudioManager.AUDIOFOCUS_LOSS||value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { stop();onInterrupted() }
            else if(value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) player?.setVolume(.25f,.25f)
            else if(value==AudioManager.AUDIOFOCUS_GAIN) player?.setVolume(.8f,.8f)
        }.build()
    fun play(song:SongExcerpt,onStarted:()->Unit,onEnded:()->Unit,onError:(String)->Unit) {
        stop();val token=generation
        try {
            val mp=MediaPlayer();player=mp;mp.setAudioAttributes(attributes);mp.setVolume(.8f,.8f)
            context.assets.openFd(song.file).use { mp.setDataSource(it.fileDescriptor,it.startOffset,it.length) }
            mp.setOnPreparedListener {
                if(token!=generation||player!==it) return@setOnPreparedListener
                if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {stop();onError("暂时无法播放，请再点一次人物");return@setOnPreparedListener}
                prepared=true;it.start();playing=true;onStarted()
            }
            mp.setOnCompletionListener { if(token==generation) {stop();onEnded()} }
            mp.setOnErrorListener { _,_,_ -> if(token==generation){stop();onError("音频播放失败，请再点一次人物")};true }
            mp.prepareAsync()
        } catch(error:Exception){stop();onError("音频未能打开，请再点一次人物")}
    }
    val elapsed:Float get()=if(prepared) try { (player?.currentPosition?:0)/1000f }catch(_:IllegalStateException){0f} else 0f
    fun stop(){generation++;playing=false;prepared=false;player?.release();player=null;manager.abandonAudioFocusRequest(focus)}
    fun close()=stop()
}
