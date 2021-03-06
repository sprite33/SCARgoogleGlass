/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.glass.voicerecognitionsample

import RetrofitService
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.glass.ui.GlassGestureDetector
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.android.synthetic.main.activity_main.*
import okhttp3.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.jvm.Throws


typealias LumaListener = (luma: Double) -> Unit


class MainActivity : AppCompatActivity(), GlassGestureDetector.OnGestureListener {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var photoUri: Uri


    private var recognized_string = " "
    private var resultTextView: TextView? = null
    private var imageView: ImageView? = null
    private var glassGestureDetector: GlassGestureDetector? = null
    private val mVoiceResults: MutableList<String> = ArrayList(4)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        resultTextView = findViewById(R.id.results)
        imageView = findViewById(R.id.img)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        glassGestureDetector = GlassGestureDetector(this, this)


        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults:
            IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                    }

            imageCapture = ImageCapture.Builder()
                    .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
                outputDirectory,
                java.text.SimpleDateFormat(FILENAME_FORMAT, Locale.US
                ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                photoUri = Uri.fromFile(photoFile)
                val imageBitmap = BitmapFactory.decodeFile(photoFile.absolutePath);
                imageView?.setImageBitmap(imageBitmap);
                val msg = "Photo captured!"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, msg)
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val results: List<String>? = data!!.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            Log.d(TAG, "results: " + results.toString())
            if (results != null && results.size > 0 && !results[0].isEmpty()) {
                updateUI(results[0])
            }
        } else {
            Log.d(TAG, "Result not OK")
        }
    }

    private val lastModified: File?
        private get() {
            val directory = filesDir
            val files = directory.listFiles()
            var lastModifiedTime = Long.MIN_VALUE
            var chosenFile: File? = null
            if (files != null) {
                for (file in files) {
                    if (file.lastModified() > lastModifiedTime) {
                        chosenFile = file
                        lastModifiedTime = file.lastModified()
                    }
                }
            }
            return chosenFile
        }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return glassGestureDetector!!.onTouchEvent(ev) || super.dispatchTouchEvent(ev)
    }

    override fun onGesture(gesture: GlassGestureDetector.Gesture): Boolean {
        return when (gesture) {
            GlassGestureDetector.Gesture.TAP -> {
                requestVoiceRecognition()
                true
            }
            GlassGestureDetector.Gesture.SWIPE_FORWARD -> {
                takePhoto()
                true
            }
            GlassGestureDetector.Gesture.SWIPE_BACKWARD -> {
                sendAPI()
                true
            }
            GlassGestureDetector.Gesture.SWIPE_DOWN -> {
                finish()
                true
            }
            else -> false
        }
    }


    private fun requestVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        // 한글인식 작동 안함.
//    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
//    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,"ko-KR");
//    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
//    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"ko-KR");
//    intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        startActivityForResult(intent, REQUEST_CODE)
    }

    private fun updateUI(result: String) {
        if (mVoiceResults.size >= 4) {
            mVoiceResults.removeAt(mVoiceResults.size - 1)
        }
        mVoiceResults.add(0, result)
        val recognizedText = java.lang.String.join(DELIMITER, mVoiceResults)
        resultTextView!!.text = recognizedText
        recognized_string = recognizedText
    }

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 672
        private const val REQUEST_CODE = 999
        private val TAG = MainActivity::class.java.simpleName
        private const val DELIMITER = "\n"
        const val REQUEST_TAKE_PHOTO = 1

        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }


    //resize bitmap for entity size
    fun resizeBitmap(source: Bitmap, maxLength: Int): Bitmap {
        try {
            if (source.height >= source.width) {
                if (source.height <= maxLength) { // if image height already smaller than the required height
                    return source
                }

                val aspectRatio = source.width.toDouble() / source.height.toDouble()
                val targetWidth = (maxLength * aspectRatio).toInt()
                val result = Bitmap.createScaledBitmap(source, targetWidth, maxLength, false)
                return result
            } else {
                if (source.width <= maxLength) { // if image width already smaller than the required width
                    return source
                }

                val aspectRatio = source.height.toDouble() / source.width.toDouble()
                val targetHeight = (maxLength * aspectRatio).toInt()

                val result = Bitmap.createScaledBitmap(source, maxLength, targetHeight, false)
                return result
            }
        } catch (e: Exception) {
            return source
        }
    }


// Mobile SCAR API Implementatation
    private fun sendAPI(){
        val bytes = File(photoUri.path).readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size )
        val resized_bitmap = resizeBitmap(bitmap,800)

        val baos = ByteArrayOutputStream()
        resized_bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val temp_b = baos.toByteArray()
        val b = Base64.encodeToString(temp_b, Base64.DEFAULT)


        val card = Card(
            authorId= "a9ygkrDuTKhBEjdrc",
            swimlaneId = "NheGqKLWdAnGMYCa3",
            title = "HG-SCAR-dt-008",
            description = recognized_string,
            file = b
        )

        var gson : Gson =  GsonBuilder()
                .setLenient()
                .create()


        //creating retrofit object
        var retrofit = Retrofit.Builder()
                    .baseUrl(" ") //!! base url required
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()

        val service = retrofit.create(RetrofitService::class.java)


        //Send card and images to server
        service.sendSCAR(card).enqueue(object : Callback<Card> {
            override fun onResponse(
                    call: Call<Card>,
                    response: Response<Card>) {
                if(response?.isSuccessful){
                    Toast.makeText(getApplicationContext(), "File Uploaded Successfully", Toast.LENGTH_LONG).show();
                    Log.d(TAG, "성공 : ${response.raw()}")
                }
                else{
                    Toast.makeText(getApplicationContext(), "Some error occurred", Toast.LENGTH_LONG).show();
                    Log.d(TAG, "response : ${response.raw()}")
                }
            }

            override fun onFailure(call: Call<Card>, t: Throwable) {
                Log.d(TAG, "실패 : $t")
            }
        })
    }


}
