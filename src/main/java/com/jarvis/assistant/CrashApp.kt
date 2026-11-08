package com.jarvis.assistant

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

class CrashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val i = Intent(this, CrashActivity::class.java)
                i.putExtra("trace", Log.getStackTraceString(e))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
            } catch (t: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = TextView(this)
        t.text = "Jarvis crashed. Send a screenshot of this:\n\n" + (intent.getStringExtra("trace") ?: "no details")
        t.setTextColor(Color.WHITE)
        t.setBackgroundColor(Color.BLACK)
        t.textSize = 11f
        t.setPadding(24, 48, 24, 24)
        val s = ScrollView(this)
        s.addView(t)
        setContentView(s)
    }
}
