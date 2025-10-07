package com.example.wechatbot.profile

import android.content.Context
import androidx.annotation.RawRes
import com.google.gson.Gson
import java.io.InputStreamReader

object ProfileLoader {
    private val gson = Gson()

    fun loadFromRawResource(context: Context, @RawRes resId: Int): AutomationProfile? {
        return try {
            context.resources.openRawResource(resId).use { stream ->
                InputStreamReader(stream).use { reader ->
                    gson.fromJson(reader, AutomationProfile::class.java)
                }
            }
        } catch (ex: Exception) {
            null
        }
    }
}
