package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class JarvisInteractionService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisSession(this)
    }
}

class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, MainActivity::class.java)
        i.action = Intent.ACTION_ASSIST
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startAssistantActivity(i)
        finish()
    }
}
