package com.example.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ai.AnswerSource
import com.example.ai.KisanAiService
import com.example.ai.PestDiagnosisResult
import com.example.ui.theme.FieldGreen
import com.example.ui.theme.NotoNastaliqUrdu
import com.example.ui.theme.WheatGold
import com.example.util.AudioHelper
import com.example.util.LocalAppLanguage
import com.example.util.isUrdu
import kotlinx.coroutines.launch
import java.io.File

private val WarningAmber = Color(0xFFB26A00)
private val WarningTint = Color(0xFFFFF4E0)

/** Reads a photo and shrinks it so its longest side is about [maxSide] pixels. Returns null if it cannot be read. */
private fun loadScaledBitmap(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun AskScreen(
    aiService: KisanAiService,
    audioHelper: AudioHelper,
    modifier: Modifier = Modifier
) {
    val isUrdu = LocalAppLanguage.current.isUrdu
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var userQueryText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var answerText by remember { mutableStateOf("") }
    var answerSource by remember { mutableStateOf<AnswerSource?>(null) }
    var voiceUnavailable by remember { mutableStateOf(false) }

    var diagnosisResult by remember { mutableStateOf<PestDiagnosisResult?>(null) }
    var isDiagnosingPhoto by remember { mutableStateOf(false) }
    var photoProblem by remember { mutableStateOf(false) }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val greeting = if (isUrdu) "السلام علیکم! بوائی، کھاد، کیڑے، موسم یا کٹائی کے بارے میں اپنا سوال بولیں یا لکھیں۔"
        else "Assalam-o-Alaikum! Speak or type a question about sowing, fertiliser, pests, weather or harvest."

    val submitQuestion: (String) -> Unit = { question ->
        if (question.isNotBlank() && !isThinking) {
            coroutineScope.launch {
                isThinking = true
                val answer = aiService.ask(question, isUrdu)
                answerText = answer.text
                answerSource = answer.source
                isThinking = false
                audioHelper.speak(answer.text)
            }
        }
    }

    val analysePhoto: (Bitmap?) -> Unit = { bitmap ->
        if (bitmap == null) {
            photoProblem = true
        } else {
            photoProblem = false
            coroutineScope.launch {
                isDiagnosingPhoto = true
                val result = aiService.diagnoseCropPhoto(bitmap, isUrdu)
                diagnosisResult = result
                isDiagnosingPhoto = false
                audioHelper.speak(if (isUrdu) result.recommendationUr else result.recommendationEn)
            }
        }
    }

    // Real speech recognition, using the recogniser already installed on the phone.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                userQueryText = spoken
                submitQuestion(spoken)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhotoUri
        if (saved && uri != null) {
            analysePhoto(loadScaledBitmap(context, uri))
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            analysePhoto(loadScaledBitmap(context, uri))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val micPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("ask_screen")
    ) {
        Text(
            text = if (isUrdu) "کسان ساتھی سے پوچھیں" else "Ask Kisan Saathi",
            fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
            fontSize = if (isUrdu) 24.sp else 21.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = if (isUrdu) "انٹرنیٹ ہو تو آن لائن معاون جواب دیتا ہے؛ نہ ہو تو جواب آپ کے کھیت کے ڈیٹا سے بنتا ہے"
                else "Online, the assistant answers. Offline, the answer is built from your field's own data.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Mic: tap to speak
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isListening) {
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .scale(micPulse)
                                .clip(CircleShape)
                                .background(FieldGreen.copy(alpha = 0.25f))
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .clickable {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (isUrdu) "ur-PK" else "en-US")
                                intent.putExtra(
                                    RecognizerIntent.EXTRA_PROMPT,
                                    if (isUrdu) "اپنا سوال بولیں" else "Speak your question"
                                )
                                try {
                                    voiceUnavailable = false
                                    isListening = true
                                    speechLauncher.launch(intent)
                                } catch (e: ActivityNotFoundException) {
                                    isListening = false
                                    voiceUnavailable = true
                                }
                            }
                            .testTag("hold_to_talk_mic_btn"),
                        color = if (isListening) Color(0xFFD32F2F) else FieldGreen,
                        shadowElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isUrdu) "بولیں" else "Speak",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isListening) {
                        if (isUrdu) "سن رہا ہوں..." else "Listening..."
                    } else {
                        if (isUrdu) "مائیک دبائیں اور سوال بولیں" else "Tap the mic and speak your question"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isListening) Color(0xFFD32F2F) else FieldGreen
                )
                if (voiceUnavailable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isUrdu) "اس فون میں آواز پہچاننے کی سہولت نہیں ملی۔ سوال نیچے لکھ دیں۔"
                            else "Speech recognition is not available on this phone. Please type your question below.",
                        fontSize = 12.sp,
                        color = WarningAmber
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Answer card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF1F8E9)
            ),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, FieldGreen.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Hearing, contentDescription = null, tint = FieldGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isUrdu) "کسان ساتھی کا جواب:" else "Kisan Saathi's answer:",
                            fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = FieldGreen
                        )
                    }

                    IconButton(
                        onClick = { audioHelper.speak(if (answerText.isBlank()) greeting else answerText) },
                        modifier = Modifier.testTag("speak_answer_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = if (isUrdu) "سنیں" else "Listen",
                            tint = FieldGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isThinking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = FieldGreen)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isUrdu) "جواب تیار ہو رہا ہے..." else "Preparing the answer...",
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        text = if (answerText.isBlank()) greeting else answerText,
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Say where the answer came from, so the farmer knows how far to trust it.
                val source = answerSource
                if (!isThinking && source != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (source) {
                            AnswerSource.ONLINE ->
                                if (isUrdu) "ماخذ: آن لائن معاون (Gemini)، آپ کے کھیت کے ڈیٹا کے ساتھ"
                                else "Source: online assistant (Gemini), given your field data"
                            AnswerSource.OFFLINE_FIELD_DATA ->
                                if (isUrdu) "ماخذ: آف لائن جواب، آپ کے کھیت کے ڈیٹا سے۔ آن لائن معاون سے رابطہ نہیں ہوا۔"
                                else "Source: offline answer from your field data. The online assistant was not reached."
                            AnswerSource.UNAVAILABLE ->
                                if (isUrdu) "جواب دستیاب نہیں"
                                else "No answer available"
                        },
                        fontSize = 11.sp,
                        color = if (source == AnswerSource.ONLINE) Color(0xFF2E7D32) else WarningAmber
                    )
                }

                if (isUrdu && !audioHelper.urduVoiceAvailable) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "اس فون میں اردو آواز نصب نہیں۔ فون کی سیٹنگز میں Text-to-speech سے اردو آواز شامل کریں۔",
                        fontSize = 11.sp,
                        color = WarningAmber
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Typed question
        OutlinedTextField(
            value = userQueryText,
            onValueChange = { userQueryText = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ask_query_input"),
            label = { Text(if (isUrdu) "یا سوال یہاں لکھیں..." else "Or type your question here...") },
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                IconButton(
                    onClick = { submitQuestion(userQueryText) },
                    modifier = Modifier.testTag("send_query_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = if (isUrdu) "بھیجیں" else "Send",
                        tint = FieldGreen
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Leaf photo check
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = WheatGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isUrdu) "پتے کی تصویر کا معائنہ" else "Leaf photo check",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isUrdu) "متاثرہ پتے کی قریب سے، دن کی روشنی میں تصویر لیں۔ معائنے کے لیے انٹرنیٹ ضروری ہے۔ یہ اندازہ ہے، حتمی تشخیص نہیں۔"
                        else "Take a close photo of the affected leaf in daylight. The check needs a connection. It is an estimate, not a diagnosis.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        try {
                            val folder = File(context.cacheDir, "scout")
                            folder.mkdirs()
                            val uri = FileProvider.getUriForFile(
                                context,
                                context.packageName + ".fileprovider",
                                File(folder, "leaf.jpg")
                            )
                            pendingPhotoUri = uri
                            cameraLauncher.launch(uri)
                        } catch (e: Exception) {
                            photoProblem = true
                        }
                    },
                    enabled = !isDiagnosingPhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("scan_crop_photo_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WheatGold)
                ) {
                    if (isDiagnosingPhoto) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isUrdu) "تصویر کا معائنہ ہو رہا ہے..." else "Checking the photo...")
                    } else {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isUrdu) "کیمرے سے تصویر لیں" else "Take a photo")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            galleryLauncher.launch("image/*")
                        } catch (e: Exception) {
                            photoProblem = true
                        }
                    },
                    enabled = !isDiagnosingPhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_crop_photo_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isUrdu) "گیلری سے تصویر چنیں" else "Choose a photo from the gallery",
                        color = FieldGreen
                    )
                }

                if (photoProblem) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isUrdu) "تصویر نہیں کھل سکی۔ دوبارہ کوشش کریں۔" else "The photo could not be opened. Please try again.",
                        fontSize = 12.sp,
                        color = WarningAmber
                    )
                }

                diagnosisResult?.let { res ->
                    val shownName = if (isUrdu) res.issueNameUr else res.issueNameEn
                    val shownAdvice = if (isUrdu) res.recommendationUr else res.recommendationEn

                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (res.analysed) Color(0xFFE8F5E9) else WarningTint,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (res.analysed) FieldGreen.copy(alpha = 0.4f) else WarningAmber.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (res.analysed) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (res.analysed) FieldGreen else WarningAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = shownName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (res.analysed) FieldGreen else WarningAmber
                                )
                                if (res.analysed && res.severityLevel.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isUrdu) "· اعتماد: ${res.severityLevel}" else "· Confidence: ${res.severityLevel}",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = shownAdvice,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = if (res.analysed) Color(0xFF1B5E20) else Color(0xFF5D4037)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Opens the phone's share sheet, so the farmer chooses how to send the result.
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                            send.type = "text/plain"
                            send.putExtra(Intent.EXTRA_TEXT, "Kisan Saathi: $shownName\n\n$shownAdvice")
                            try {
                                context.startActivity(
                                    Intent.createChooser(send, if (isUrdu) "زراعت افسر کو بھیجیں" else "Share with extension officer")
                                )
                            } catch (e: Exception) {
                                photoProblem = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("escalate_officer_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.SupportAgent, contentDescription = null, tint = FieldGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isUrdu) "نتیجہ زراعت افسر کو بھیجیں" else "Share the result with your extension officer",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FieldGreen
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
