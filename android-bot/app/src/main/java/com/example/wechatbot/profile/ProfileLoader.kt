package com.example.wechatbot.profile

import android.content.Context
import androidx.annotation.RawRes
import com.google.gson.Gson
import java.io.InputStreamReader

object ProfileLoader {
    private val gson = Gson()

    fun loadFromRawResource(context: Context, @RawRes resId: Int): AutomationProfile? {
        return runCatching {
            context.resources.openRawResource(resId).use { stream ->
                InputStreamReader(stream).use { reader ->
                    gson.fromJson(reader, AutomationProfile::class.java)
                }
            }
        }.getOrNull()
    }

    fun loadFromString(json: String): AutomationProfile? {
        return runCatching {
            gson.fromJson(json, AutomationProfile::class.java)
        }.getOrNull()
    }

    fun readRawText(context: Context, @RawRes resId: Int): String? {
        return runCatching {
            context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    fun toJson(profile: AutomationProfile): String {
        return gson.toJson(profile)
    }
}
