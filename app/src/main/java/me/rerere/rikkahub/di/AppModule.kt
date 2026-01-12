package me.rerere.rikkahub.di

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.WelcomePhrasesService
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskScheduler
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

private const val TAG = "AppModule"

private fun isFirebaseInitialized(): Boolean {
    return try {
        FirebaseApp.getInstance()
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Firebase not initialized: ${e.message}")
        false
    }
}

fun getRemoteConfigOrNull(): FirebaseRemoteConfig? {
    return if (isFirebaseInitialized()) {
        try {
            Firebase.remoteConfig
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get RemoteConfig", e)
            null
        }
    } else null
}

fun getCrashlyticsOrNull(): FirebaseCrashlytics? {
    return if (isFirebaseInitialized()) {
        try {
            Firebase.crashlytics
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Crashlytics", e)
            null
        }
    } else null
}

fun getAnalyticsOrNull(): FirebaseAnalytics? {
    return if (isFirebaseInitialized()) {
        try {
            Firebase.analytics
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Analytics", e)
            null
        }
    } else null
}

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        LocalTools(get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    getCrashlyticsOrNull()?.let { crashlytics ->
        single { crashlytics }
    }

    getRemoteConfigOrNull()?.let { remoteConfig ->
        single { remoteConfig }
    }

    getAnalyticsOrNull()?.let { analytics ->
        single { analytics }
    }

    single {
        AILoggingManager()
    }

    single {
        AIRequestLogManager(dao = get())
    }

    single {
        WelcomePhrasesService(
            settingsStore = get(),
            providerManager = get(),
            memoryRepository = get(),
            requestLogManager = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            toolResultArchiveRepository = get(),
            memoryRepository = get(),
            generationHandler = get(),
            requestLogManager = get(),
            templateTransformer = get(),
            providerManager = get(),
            embeddingService = get(),
            lorebookEntryRevisionRepository = get(),
            localTools = get(),
            okHttpClient = get(),
            mcpManager = get()
        )
    }

    single {
        ScheduledTaskScheduler(
            context = get(),
            taskDao = get()
        )
    }
}
