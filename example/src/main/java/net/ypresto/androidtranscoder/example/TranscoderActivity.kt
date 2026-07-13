package net.ypresto.androidtranscoder.example

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import net.ypresto.androidtranscoder.MediaTranscoder
import net.ypresto.androidtranscoder.format.MediaFormatStrategyPresets
import net.ypresto.androidtranscoder.nativeh265.NativeH265Codec
import net.ypresto.androidtranscoder.nativeh265.NativeH265Transcoder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Future
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class TranscoderActivity : ComponentActivity() {
    private var uiState by mutableStateOf(TranscoderUiState())
    private var transcodeFuture: Future<Void>? = null
    private var nativeTranscodeFuture: Future<*>? = null
    private val nativeExecutor = Executors.newSingleThreadExecutor()
    private var inputFileDescriptor: ParcelFileDescriptor? = null
    private var outputFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = uiState.copy(status = getString(R.string.status_ready))
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TranscoderDemo(
                        state = uiState,
                        onSelectVideo = ::selectVideo,
                        onCodecSelected = { useH265 -> uiState = uiState.copy(useH265 = useH265) },
                        onCancel = ::cancelTranscoding,
                        onOpenOutput = ::openOutputVideo
                    )
                }
            }
        }
    }

    private fun selectVideo(uri: Uri) {
        val useH265 = uiState.useH265
        val newOutputFile = createOutputFile()
        if (newOutputFile == null) {
            showError(getString(R.string.status_output_error))
            return
        }

        val descriptor = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (exception: FileNotFoundException) {
            Log.w(TAG, "Could not open input video: $uri", exception)
            newOutputFile.delete()
            showError(getString(R.string.status_input_error))
            return
        } catch (exception: SecurityException) {
            Log.w(TAG, "Permission denied for input video: $uri", exception)
            newOutputFile.delete()
            showError(getString(R.string.status_input_error))
            return
        }

        if (descriptor == null) {
            newOutputFile.delete()
            showError(getString(R.string.status_input_error))
            return
        }

        outputFile = newOutputFile
        inputFileDescriptor = descriptor
        uiState = TranscoderUiState(
            inputName = getDisplayName(uri),
            outputPath = newOutputFile.absolutePath,
            status = getString(R.string.status_transcoding),
            progress = 0f,
            isTranscoding = true,
            useH265 = useH265
        )

        val source = descriptor.fileDescriptor
        if (useH265 && NativeH265Codec.isAvailable()) {
            val startTime = SystemClock.uptimeMillis()
            nativeTranscodeFuture = nativeExecutor.submit {
                try {
                    NativeH265Transcoder.transcode(source, newOutputFile.absolutePath, VIDEO_BITRATE)
                    runOnUiThread {
                        Log.d(TAG, "Native H.265 transcoding completed in ${SystemClock.uptimeMillis() - startTime}ms")
                        finishTranscoding(succeeded = true, status = getString(R.string.status_completed))
                    }
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runOnUiThread {
                        finishTranscoding(succeeded = false, status = getString(R.string.status_canceled))
                    }
                } catch (exception: Exception) {
                    Log.e(TAG, "Native H.265 transcoding failed", exception)
                    runOnUiThread {
                        val detail = exception.message ?: exception.javaClass.simpleName
                        finishTranscoding(succeeded = false, status = getString(R.string.status_failed, detail))
                    }
                }
            }
            return
        }

        val startTime = SystemClock.uptimeMillis()
        val future = MediaTranscoder.getInstance().transcodeVideo(
            source,
            newOutputFile.absolutePath,
            if (useH265) {
                MediaFormatStrategyPresets.createAndroidH265BitrateFormatStrategy(VIDEO_BITRATE)
            } else {
                MediaFormatStrategyPresets.createAndroidBitrateFormatStrategy(VIDEO_BITRATE)
            },
            object : MediaTranscoder.Listener {
                override fun onTranscodeProgress(progress: Double) {
                    uiState = uiState.copy(progress = progress.toFloat().takeIf { it >= 0f })
                }

                override fun onTranscodeCompleted() {
                    Log.d(TAG, "Transcoding completed in ${SystemClock.uptimeMillis() - startTime}ms")
                    finishTranscoding(succeeded = true, status = getString(R.string.status_completed))
                }

                override fun onTranscodeCanceled() {
                    finishTranscoding(succeeded = false, status = getString(R.string.status_canceled))
                }

                override fun onTranscodeFailed(exception: Exception) {
                    Log.e(TAG, "Transcoding failed", exception)
                    val detail = exception.message ?: exception.javaClass.simpleName
                    finishTranscoding(succeeded = false, status = getString(R.string.status_failed, detail))
                }
            }
        )
        if (uiState.isTranscoding) {
            transcodeFuture = future
        }
    }

    private fun cancelTranscoding() {
        transcodeFuture?.let {
            uiState = uiState.copy(status = getString(R.string.status_cancelling))
            it.cancel(true)
        }
        nativeTranscodeFuture?.let {
            uiState = uiState.copy(status = getString(R.string.status_cancelling))
            it.cancel(true)
        }
    }

    private fun finishTranscoding(succeeded: Boolean, status: String) {
        closeInputFileDescriptor()
        transcodeFuture = null
        nativeTranscodeFuture = null
        if (succeeded) {
            uiState = uiState.copy(
                status = status,
                progress = 1f,
                isTranscoding = false,
                canOpenOutput = true
            )
        } else {
            deleteOutputFile()
            uiState = uiState.copy(
                outputPath = null,
                status = status,
                progress = 0f,
                isTranscoding = false,
                canOpenOutput = false
            )
        }
    }

    private fun createOutputFile(): File? {
        val externalFilesDir = getExternalFilesDir(null) ?: return null
        val outputDirectory = File(externalFilesDir, "outputs")
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            return null
        }
        return try {
            File.createTempFile("transcoded_", ".mp4", outputDirectory)
        } catch (exception: IOException) {
            Log.e(TAG, "Failed to create output file", exception)
            null
        }
    }

    private fun openOutputVideo() {
        val file = outputFile ?: return
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_video_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun getDisplayName(uri: Uri): String {
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun showError(message: String) {
        uiState = uiState.copy(status = message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun closeInputFileDescriptor() {
        try {
            inputFileDescriptor?.close()
        } catch (exception: IOException) {
            Log.w(TAG, "Failed to close input file descriptor", exception)
        } finally {
            inputFileDescriptor = null
        }
    }

    private fun deleteOutputFile() {
        outputFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete partial output file: $file")
            }
        }
        outputFile = null
    }

    override fun onDestroy() {
        if (isFinishing) {
            transcodeFuture?.cancel(true)
            nativeTranscodeFuture?.cancel(true)
        }
        nativeExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranscoderActivity"
        private const val FILE_PROVIDER_AUTHORITY = "net.ypresto.androidtranscoder.example.fileprovider"
        private const val VIDEO_BITRATE = 8_000_000
    }
}

private data class TranscoderUiState(
    val inputName: String? = null,
    val outputPath: String? = null,
    val status: String = "",
    val progress: Float? = 0f,
    val isTranscoding: Boolean = false,
    val canOpenOutput: Boolean = false,
    val useH265: Boolean = false
)

@Composable
private fun TranscoderDemo(
    state: TranscoderUiState,
    onSelectVideo: (Uri) -> Unit,
    onCodecSelected: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onOpenOutput: () -> Unit
) {
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onSelectVideo(uri)
    }
    val progressLabel = when {
        state.isTranscoding && state.progress == null -> stringResource(R.string.progress_unknown)
        state.isTranscoding || state.canOpenOutput -> stringResource(R.string.progress_percent, ((state.progress ?: 0f) * 100).roundToInt())
        else -> stringResource(R.string.progress_not_started)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = stringResource(R.string.demo_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.demo_description), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = { videoPicker.launch("video/*") },
            enabled = !state.isTranscoding,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.select_video))
        }
        Text(
            text = state.inputName ?: stringResource(R.string.no_video_selected),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(text = stringResource(R.string.output_codec), style = MaterialTheme.typography.titleMedium)
        Column {
            CodecChoice(
                label = stringResource(R.string.codec_h264),
                selected = !state.useH265,
                enabled = !state.isTranscoding,
                onClick = { onCodecSelected(false) }
            )
            CodecChoice(
                label = stringResource(R.string.codec_h265),
                selected = state.useH265,
                enabled = !state.isTranscoding && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    || NativeH265Codec.isAvailable()),
                onClick = { onCodecSelected(true) }
            )
        }

        HorizontalDivider()
        Text(text = state.status, style = MaterialTheme.typography.titleMedium)
        if (state.isTranscoding && state.progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { state.progress ?: 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(text = progressLabel, style = MaterialTheme.typography.bodySmall)

        if (state.isTranscoding) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel_transcoding))
            }
        }
        if (state.canOpenOutput) {
            Button(onClick = onOpenOutput, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_output))
            }
        }
        Text(
            text = state.outputPath?.let { stringResource(R.string.output_file, it) }
                ?: stringResource(R.string.no_output_created),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CodecChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Text(text = label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Preview(showBackground = true)
@Composable
private fun TranscoderDemoPreview() {
    MaterialTheme {
        TranscoderDemo(
            state = TranscoderUiState(
                inputName = "sample.mp4",
                outputPath = "/storage/emulated/0/Android/data/example/files/outputs/transcoded.mp4",
                status = "Completed",
                progress = 1f,
                canOpenOutput = true
            ),
            onSelectVideo = {},
            onCodecSelected = {},
            onCancel = {},
            onOpenOutput = {}
        )
    }
}
