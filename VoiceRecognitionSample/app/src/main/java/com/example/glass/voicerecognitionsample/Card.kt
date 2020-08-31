package com.example.glass.voicerecognitionsample

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.annotations.SerializedName
import java.io.File

data class Card (
    var authorId: String,
    var title: String,
    var description: String,
    var swimlaneId: String,
    var file: String?
    )
