package com.intentlock.app.prediction

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intentlock.app.BuildConfig
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.data.AppSchedule
import com.intentlock.app.data.PredictionResult
import com.intentlock.app.data.UnlockContext
import com.intentlock.app.data.UnlockEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class GeminiPredictor(private val context: Context) {

    private val api: GeminiApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeminiApi::class.java)
    }

    suspend fun predictMultiple(
        unlockContext: UnlockContext,
        installedApps: List<String>
    ): List<PredictionResult> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val schedules = db.unlockDao().getAllSchedules()
            val history = db.unlockDao().getRecentEvents(50)

            val prompt = buildPrompt(unlockContext, installedApps, schedules, history)
            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt))))
            )

            val response = api.predict(BuildConfig.GEMINI_API_KEY, request)

            if (response.isSuccessful) {
                val raw = response.body()
                    ?.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()
                    ?.text ?: run {
                        Log.e(TAG, "Gemini: Empty response body")
                        return@withContext emptyList()
                    }

                val json = raw.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val results = mutableListOf<PredictionResult>()
                val jsonArray = try {
                    Gson().fromJson(json, JsonArray::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini: JSON parse error: $json", e)
                    return@withContext emptyList()
                }
                
                for (i in 0 until jsonArray.size()) {
                    val obj = jsonArray.get(i).asJsonObject
                    val pkg = obj.get("package").asString
                    val confidence = obj.get("confidence").asInt
                    val reason = obj.get("reason")?.asString ?: ""

                    if (installedApps.contains(pkg)) {
                        results.add(PredictionResult(
                            packageName = pkg,
                            appName = getAppName(pkg),
                            confidence = confidence,
                            autoLaunch = false,
                            reason = reason
                        ))
                    }
                }
                results.sortedByDescending { it.confidence }
            } else {
                Log.e(TAG, "Gemini API failed: ${response.code()} ${response.message()}")
                Log.e(TAG, "Gemini Error Body: ${response.errorBody()?.string()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini error: ${e.message}")
            emptyList()
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun buildPrompt(
        ctx: UnlockContext, 
        installedApps: List<String>, 
        schedules: List<AppSchedule>,
        history: List<UnlockEvent>
    ): String {
        val scheduleText = schedules.joinToString("\n") { 
            "- ${it.appName} (${it.packageName}) between ${it.startHour}:00 and ${it.endHour}:00" 
        }
        
        val historyText = history.take(10).joinToString("\n") {
            "- Used ${it.packageName} at ${it.hour}:00 on ${it.dayOfWeek}"
        }

        return """
You are an AI for IntentLock. Your goal is to predict which apps a user might want to open upon unlocking their phone.
Provide a list of the top 3 most likely applications.

USER'S DEFINED SCHEDULE (High Priority):
${if (scheduleText.isEmpty()) "No schedule defined." else scheduleText}

USER'S RECENT BEHAVIOR (Patterns):
${if (historyText.isEmpty()) "No recent history." else historyText}

CURRENT CONTEXT:
- Time: ${ctx.hour}:00, ${ctx.dayOfWeek}
- Recently used: ${ctx.recentApps.joinToString(", ")}
- Battery: ${ctx.batteryLevel}%, Charging: ${ctx.isCharging}

AVAILABLE APPS:
${installedApps.joinToString(", ")}

RULES:
1. Return a JSON array of up to 3 predictions.
2. Prioritize scheduled apps if the current time matches.
3. Include a short "reason" for each prediction (e.g., "In schedule", "Used recently", "Frequent morning habit").
4. Respond ONLY with a valid JSON array of objects.
   Format: [{"package": "com.example.app", "confidence": 85, "reason": "Work schedule"}, ...]
        """.trimIndent()
    }

    companion object {
        private const val TAG = "IntentLock"
    }
}
