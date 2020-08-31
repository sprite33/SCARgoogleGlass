import com.example.glass.voicerecognitionsample.Card
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.*
import java.util.*
import kotlin.collections.HashMap


interface RetrofitService{
    @Headers("Authorization: ") // Authorization key required
    @POST("cards")
    fun sendSCAR(
            @Body body: Card
    ):Call<Card>
}
