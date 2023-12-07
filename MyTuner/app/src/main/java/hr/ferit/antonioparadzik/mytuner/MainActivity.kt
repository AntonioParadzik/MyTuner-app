package hr.ferit.antonioparadzik.mytuner

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import kotlin.math.log

class MainActivity : AppCompatActivity() {
    private val sampleRate = 44100
    private var isRecording=false
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize =7056
    private lateinit var frequency: TextView
    private lateinit var note: TextView
    private lateinit var audioRecord: AudioRecord
    private var isAudioRecordInitialized = false
    private val referenceFrequencyDict=mapOf('E' to 82.41, 'A' to 110.00, 'D' to 146.83, 'G' to 196.00, 'B' to 246.94, 'e' to 329.63)
    private lateinit var wheelImageView: ImageView
    private val circularBufferSize = 8
    private val circularBuffer = FloatArray(circularBufferSize)
    private var circularBufferIndex = 0
    private val spanFrequenciesCount = mutableMapOf<String, Int>()
    private val spans = listOf(
        "E_span" to 80.06f..84.82f,
        "A_span" to 106.87f..113.22f,
        "D_span" to 142.65f..151.13f,
        "G_span" to 190.42f..201.74f,
        "B_span" to 239.91f..254.18f,
        "e_span" to 320.25f..339.29f
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val initialWheelAnimation = AnimationUtils.loadAnimation(this, R.anim.rotation)
        wheelImageView = findViewById(R.id.wheelImage)
        wheelImageView.startAnimation(initialWheelAnimation)
        frequency = findViewById(R.id.frequencyText)
        note=findViewById(R.id.noteText)

        requestRecordAudioPermission()
    }

    override fun onStart() {
        super.onStart()

        startRecording()
    }
    private fun requestRecordAudioPermission() {
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                try {
                    if (!isAudioRecordInitialized) {
                        audioRecord = AudioRecord(
                            audioSource,
                            sampleRate,
                            channelConfig,
                            audioFormat,
                            bufferSize
                        )
                        isAudioRecordInitialized = true
                    }
                    startRecording()
                } catch (e: Exception) {
                    Log.e("TAG", "Error initializing AudioRecord: ${e.message}")
                }
            } else {
                Log.d("TAG", "Permission denied by user")
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            if (!isAudioRecordInitialized) {
                audioRecord = AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                isAudioRecordInitialized = true
            }
            startRecording()
        }
    }

    private fun startRecording() {
        /* Inicijalizacija varijabli i objekata */
        isRecording = true
        val buffer = ShortArray(bufferSize)
        audioRecord.startRecording()
        val tarsosDSPAudioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(),
            16, 1, true, false)
        val audioEvent = AudioEvent(tarsosDSPAudioFormat)

        /* Inicijalizacija pitchDetectionHandlera */
        val pitchDetectionHandler = object : PitchDetectionHandler {
            /* Implementacija handlePitch() callback funkcije */
            override fun handlePitch(pitchDetectionResult: PitchDetectionResult, audioEvent: AudioEvent) {
                val pitchInHz=pitchDetectionResult.pitch
                runOnUiThread {
                    if(pitchInHz != -1F) {
                        val isInSpan = spans.any { (_, spanRange) -> pitchInHz in spanRange }
                        if (isInSpan) {
                            circularBuffer[circularBufferIndex] = pitchInHz
                            if (circularBufferIndex == circularBufferSize - 1) {
                                calculatePitch()

                            }
                            circularBufferIndex = (circularBufferIndex + 1) % circularBufferSize
                        }
                    }
                    else{
                        frequency.text = "Play a note"
                        frequency.setTextColor(Color.WHITE)
                        note.text = "Note"
                        note.setTextColor(Color.WHITE)
                        wheelImageView.rotation = 0F
                    }
                }
            }
        }

        /* Inicijalizacija pitchProcessora */
        val pitchProcessor = PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.YIN,
            sampleRate.toFloat(), bufferSize, pitchDetectionHandler)

        /* Pokretanje niti koja učitava zvuk s mikrofona i procesira ga */
        Thread {
            val floatBuffer = FloatArray(bufferSize)
            while (isRecording) {
                audioRecord.read(buffer, 0, bufferSize)
                for (i in 0 until bufferSize) {
                    floatBuffer[i] = buffer[i].toFloat() / 32768.0f // Pretvaranje short u float u rasponu [-1.0, 1.0]
                }

                audioEvent.floatBuffer=floatBuffer
                pitchProcessor.process(audioEvent)
            }
        }.start()
    }

    private fun getMajoritySpan(circularBuffer:FloatArray):String{
        spanFrequenciesCount.clear()
        for ((spanLabel, spanRange) in spans) {
            val count = circularBuffer.count { frequency ->
                frequency in spanRange
            }
            spanFrequenciesCount[spanLabel] = count
        }
        val majoritySpan = spanFrequenciesCount.maxByOrNull { it.value }?.key
        return majoritySpan.toString()
    }

    private fun calculatePitch(){
        val majoritySpan = getMajoritySpan(circularBuffer)
        val filteredFrequencies = circularBuffer.filter { frequency ->
            frequency in spans.first { it.first == majoritySpan }.second
        }

        var sum = 0f
        for (element in filteredFrequencies) {
            sum += element
        }
        val average=sum / filteredFrequencies.size
        val pitchInCents=1200* log(average/ referenceFrequencyDict[majoritySpan[0]]!!,2.0)

        displayPitchAndNote(pitchInCents, majoritySpan)
    }

    private fun displayPitchAndNote(pitchInCents:Double, majoritySpan:String){
        if (pitchInCents >= -6 && pitchInCents <= 6) {
            note.setTextColor(Color.GREEN)
            frequency.setTextColor(Color.GREEN)
        } else {
            note.setTextColor(Color.WHITE)
            frequency.setTextColor(Color.WHITE)
        }
        note.text= majoritySpan[0].toString()
        frequency.text = String.format("%.2f", pitchInCents)
        calculateRotationAngle(pitchInCents)
    }

    private fun calculateRotationAngle(pitchDifference: Double) {
        val rotationAngle=pitchDifference*1.8f
        startRotation(wheelImageView, rotationAngle.toFloat())
    }

    private fun startRotation(wheelImageView: ImageView,rotationAngle:Float){
        val rotationAnimator = ObjectAnimator.ofFloat(wheelImageView, "rotation",
            wheelImageView.rotation, rotationAngle)
        rotationAnimator.duration = 500
        rotationAnimator.interpolator = DecelerateInterpolator()
        rotationAnimator.start()
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord.stop()
        audioRecord.release()
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
        wheelImageView.clearAnimation()
    }
}