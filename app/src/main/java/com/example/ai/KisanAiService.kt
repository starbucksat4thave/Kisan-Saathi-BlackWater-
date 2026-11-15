package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.repository.KisanRepository
import com.example.engine.Dates
import com.example.engine.KisanAnswers
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

private const val TAG = "KisanAiService"

/** Where an answer came from. The Ask screen shows this to the farmer. */
enum class AnswerSource {
    /** Written by the online assistant (Gemini through Firebase). */
    ONLINE,

    /** Worked out on the phone from the field's own data, because the assistant could not be reached. */
    OFFLINE_FIELD_DATA,

    /** Neither the assistant nor the field data was available. The text says so. */
    UNAVAILABLE
}

class AiAnswer(val text: String, val source: AnswerSource)

data class PestDiagnosisResult(
    val issueNameUr: String,
    val issueNameEn: String,
    val severityLevel: String, // the assistant's stated confidence: low, medium or high
    val isPesticideRecommended: Boolean, // always false: the app never recommends a spray from a photo
    val recommendationUr: String,
    val recommendationEn: String,
    /** False when the photo was NOT examined (no connection, or the assistant failed). */
    val analysed: Boolean = true
)

/**
 * Questions and leaf photos.
 *
 * Online, both go to Gemini through the Firebase project in google-services.json, so no API key
 * lives in the app. If that call fails:
 *  - a question is answered from the on-device engine and labelled as an offline answer;
 *  - a photo is reported as NOT analysed. The app never invents a diagnosis.
 */
class KisanAiService(private val repository: KisanRepository? = null) {

    // Lazy, so a missing Firebase setup cannot crash the app at start-up.
    private val textModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    /** Answer a farmer's question in Urdu or English, saying where the answer came from. */
    suspend fun ask(userQuery: String, isUrdu: Boolean): AiAnswer = withContext(Dispatchers.IO) {
        val prompt = buildQuestionPrompt(userQuery, isUrdu)
        try {
            val response = withTimeout(30_000L) {
                textModel.generateContent(prompt)
            }
            val text = response.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return@withContext AiAnswer(text, AnswerSource.ONLINE)
            }
            Log.w(TAG, "Assistant returned an empty answer")
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Assistant timed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Assistant call failed", e)
        }
        offlineAnswer(userQuery, isUrdu)
    }

    /** Kept for older callers. Prefer [ask], which also reports the source of the answer. */
    suspend fun askAgronomyQuestion(
        userQuery: String,
        currentStage: String = "",
        village: String = "Dhudial, Chakwal"
    ): String = ask(userQuery, isUrdu = true).text

    private fun offlineAnswer(userQuery: String, isUrdu: Boolean): AiAnswer {
        val engine = repository?.engine
        val snap = repository?.getLatestSnapshot()
        if (engine != null && snap != null) {
            val local = runCatching { KisanAnswers.answer(engine, snap, userQuery) }.getOrNull()
            if (local != null) {
                return AiAnswer(if (isUrdu) local.textUr else local.textEn, AnswerSource.OFFLINE_FIELD_DATA)
            }
        }
        return AiAnswer(
            if (isUrdu) "اس وقت معاون سے رابطہ نہیں ہو سکا اور کھیت کا ڈیٹا بھی دستیاب نہیں۔ انٹرنیٹ چیک کر کے دوبارہ کوشش کریں، یا اپنے زراعت افسر سے پوچھیں۔"
            else "The assistant could not be reached and the field data is not loaded. Check your connection and try again, or ask your extension officer.",
            AnswerSource.UNAVAILABLE
        )
    }

    /** Today's real conditions for the field, given to the assistant so its answer is grounded. */
    private fun fieldContext(): String {
        val snap = repository?.getLatestSnapshot() ?: return "Field data: not available."
        fun n(v: Double?) = if (v == null) "unknown" else String.format(Locale.US, "%.0f", v)
        return """
            Field data computed on the phone (Dhudial area, Chakwal), as of ${Dates.format(snap.dataAsOf)}:
            - crop stage: ${KisanAnswers.stageLabel(snap.stage, false)} (${n(snap.gdd)} growing degree days)
            - estimated surface soil moisture: ${n(snap.soilMoisture * 100)}% (an estimate from rain and evaporation, not a sensor)
            - rain in the last 7 days: ${n(snap.rain7d)} mm
            - rain expected: ${n(snap.fcRain48h)} mm in 2 days, ${n(snap.fcRain7d)} mm in 7 days
            - forecast minimum next 3 nights: ${n(snap.fcTmin3d)} C; forecast maximum next 5 days: ${n(snap.fcTmax5d)} C
        """.trimIndent()
    }

    private fun buildQuestionPrompt(userQuery: String, isUrdu: Boolean): String {
        val language = if (isUrdu) "simple, natural Urdu (اردو), addressing the farmer respectfully as آپ" else "simple English"
        return """
            You are 'Kisan Saathi' (کسان ساتھی), a companion for smallholder rainfed (barani) wheat farmers in Pothohar, Pakistan.

            ${fieldContext()}

            Farmer question: "$userQuery"

            Rules:
            1. Answer in $language. Keep it short enough to be read aloud: four sentences at most.
            2. Use the field data above when it is relevant, and say plainly if it does not cover the question.
            3. Never name a pesticide, a fertiliser dose or a seed rate. For those, tell the farmer to ask the local extension officer.
            4. For pests and diseases, advise scouting first (20 plants in 4 spots of the field) before any spray.
            5. If you are not sure, say so. Do not guess.
        """.trimIndent()
    }

    /**
     * Look at a leaf photo. If the assistant cannot be reached the result has analysed = false and
     * says the photo was not checked.
     */
    suspend fun diagnoseCropPhoto(bitmap: Bitmap, isUrdu: Boolean = true): PestDiagnosisResult = withContext(Dispatchers.IO) {
        val prompt = """
            This photograph was taken by a wheat farmer in Pothohar, Pakistan.
            Look for: aphids, yellow (stripe) rust, termite damage, nutrient deficiency, or a healthy crop.
            If the photo is blurred, too dark, or does not show a wheat plant, the finding is "unclear".
            Never name a pesticide or a dose.

            Reply in exactly these four lines and nothing else:
            FINDING: one of aphids, yellow_rust, termite, nutrient_deficiency, healthy, unclear
            CONFIDENCE: one of low, medium, high
            NOTE_UR: one or two short sentences in Urdu on what you see
            NOTE_EN: the same in English
        """.trimIndent()

        try {
            val response = withTimeout(45_000L) {
                textModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }
            val text = response.text.orEmpty()
            val finding = field(text, "FINDING")?.lowercase(Locale.US)?.replace(' ', '_')
            if (finding == null) {
                Log.w(TAG, "Photo reply did not follow the expected format")
                return@withContext notAnalysed()
            }
            val confidence = field(text, "CONFIDENCE")?.lowercase(Locale.US) ?: "low"
            val names = FINDINGS[finding] ?: FINDINGS.getValue("unclear")
            val noteUr = field(text, "NOTE_UR").orEmpty()
            val noteEn = field(text, "NOTE_EN").orEmpty()
            PestDiagnosisResult(
                issueNameUr = names.first,
                issueNameEn = names.second,
                severityLevel = confidence,
                isPesticideRecommended = false,
                recommendationUr = (noteUr + " " + NEXT_STEP_UR).trim(),
                recommendationEn = (noteEn + " " + NEXT_STEP_EN).trim(),
                analysed = true
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Photo check timed out", e)
            notAnalysed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Photo check failed", e)
            notAnalysed()
        }
    }

    private fun field(text: String, key: String): String? =
        text.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }

    private fun notAnalysed() = PestDiagnosisResult(
        issueNameUr = "تصویر کا معائنہ نہیں ہو سکا",
        issueNameEn = "Photo could not be analysed",
        severityLevel = "",
        isPesticideRecommended = false,
        recommendationUr = "معاون سے رابطہ نہیں ہو سکا، اس لیے اس تصویر کا معائنہ نہیں ہوا اور پتے کے بارے میں کچھ معلوم نہیں۔ انٹرنیٹ چیک کر کے دوبارہ کوشش کریں، یا کھیت کی چار جگہوں سے بیس پودے دیکھیں اور پتہ اپنے زراعت افسر کو دکھائیں۔",
        recommendationEn = "The assistant could not be reached, so this photo was not checked and nothing is known about the leaf. Check your connection and try again, or check 20 plants in 4 spots of the field and show the leaf to your extension officer.",
        analysed = false
    )

    private companion object {
        val FINDINGS = mapOf(
            "aphids" to ("سست تیلہ" to "Aphids"),
            "yellow_rust" to ("زرد کنگی" to "Yellow rust"),
            "termite" to ("دیمک" to "Termite damage"),
            "nutrient_deficiency" to ("غذائی کمی" to "Nutrient deficiency"),
            "healthy" to ("صحت مند فصل" to "Healthy crop"),
            "unclear" to ("تصویر واضح نہیں" to "Photo unclear")
        )
        const val NEXT_STEP_UR = "یہ تصویر پر مبنی اندازہ ہے، حتمی تشخیص نہیں۔ اسپرے کا فیصلہ کرنے سے پہلے کھیت کی چار جگہوں سے بیس پودے دیکھیں اور زراعت افسر سے تصدیق کریں۔"
        const val NEXT_STEP_EN = "This is an estimate from one photo, not a diagnosis. Before deciding on any spray, check 20 plants in 4 spots of the field and confirm with your extension officer."
    }
}
