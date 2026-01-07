package com.example.mapcollection.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

data class ProfileRes(
    val email: String = "",
    val userName: String = "",
    val userLabel: String = "",
    val introduction: String = "",
    val photoUrl: String? = null,
    val firstLogin: Boolean = true
)

data class UpdateProfileReq(
    val email: String,
    val userName: String,
    val userLabel: String,
    val introduction: String,
    val firstLogin: Boolean,
    val photoUrl: String? = null
)

data class UploadPhotoRes(val photoUrl: String)

data class AiAskReq(val prompt: String, val model: String? = null)
data class AiAskRes(val text: String)

data class MyPostRes(
    val id: String,
    val mapName: String,
    val mapType: String,
    val createdAtMillis: Long,
    val isRecommended: Boolean
)

data class CreatePostReq(
    val email: String,
    val mapName: String,
    val mapType: String,
    val isRecommended: Boolean
)

data class CreatePostRes(val id: String)

data class UpdatePostReq(
    val email: String,
    val mapName: String,
    val mapType: String
)

data class PostDetailRes(
    val id: String,
    val ownerEmail: String,
    val mapName: String,
    val mapType: String,
    val isRecommended: Boolean
)

data class RecommendedPostRes(
    val id: String? = null,
    val mapName: String? = null,
    val mapType: String? = null
)

data class SpotRes(
    val id: String,
    val name: String,
    val description: String,
    val lat: Double,
    val lng: Double,
    val photoUrl: String? = null
)

data class CreateSpotReq(
    val email: String,
    val name: String,
    val description: String,
    val lat: Double,
    val lng: Double
)

data class CreateSpotRes(val id: String)

data class UpdateSpotReq(
    val email: String,
    val name: String,
    val description: String
)

data class OkRes(val ok: Boolean = true)

data class TripRes(
    val id: String,
    val ownerEmail: String,
    val title: String,
    val collaborators: List<String> = emptyList(),
    val createdAtMillis: Long? = null,
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null,
    val days: Int = 7
)

data class CreateTripReq(
    val email: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long
)

data class CreateTripRes(val id: String)

data class RenameTripReq(val email: String, val title: String)

data class ChangeTripDatesReq(val email: String, val startMillis: Long, val endMillis: Long)

data class UpdateTripReq(
    val email: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long
)

data class PublicPostRes(
    val id: String,
    val ownerEmail: String,
    val mapName: String,
    val mapType: String,
    val createdAtMillis: Long,
    val likes: Int = 0,
    val isRecommended: Boolean = false
)

data class RegisterReq(
    val email: String,
    val password: String
)


data class SearchPostRes(
    val id: String,
    val ownerEmail: String,
    val mapName: String,
    val mapType: String,
    val createdAtMillis: Long
)

data class PublicUserProfileRes(
    val email: String,
    val userName: String,
    val userLabel: String,
    val introduction: String,
    val photoUrl: String? = null
)

data class TripStopRes(
    val id: String,
    val name: String,
    val description: String,
    val lat: Double,
    val lng: Double,
    val photoUrl: String? = null,
    val startTime: String = "",
    val endTime: String = "",
    val aiSuggestion: String = "",
    val category: String = "景點",
    val createdAtMillis: Long = 0L
)


data class AddTripStopReq(
    val lat: Double,
    val lng: Double,
    val name: String,
    val category: String = "景點",
    val description: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val placeId: String? = null
)

data class AiVoiceReq(
    val text: String,
    val spotName: String,
    val desc: String,
    val startTime: String,
    val endTime: String,
    val lat: Double?,
    val lng: Double?
)

data class AiVoiceRes(val text: String)


data class AiTextRes(val text: String)


interface ApiService {
    // =====search=====
    @GET("posts/search")
    suspend fun searchPosts(
        @Query("q") q: String,
        @Query("limit") limit: Int = 300
    ): List<SearchPostRes>


    // ===== Public Posts（RecommendActivity 用）=====
    @GET("posts/public")
    suspend fun getPublicPosts(
        @Query("limit") limit: Int = 300
    ): List<PublicPostRes>


    // ===== Profile =====
    @GET("me/profile")
    suspend fun getMyProfile(@Query("email") email: String): ProfileRes

    @PUT("me/profile")
    suspend fun updateMyProfile(@Body req: UpdateProfileReq): OkRes

    @Multipart
    @POST("me/profile/photo")
    suspend fun uploadMyPhoto(
        @Part("email") email: RequestBody,
        @Part photo: MultipartBody.Part
    ): UploadPhotoRes

    // ===== Favorites =====
    @GET("me/favorites")
    suspend fun getMyFavorites(@Query("email") email: String): List<FavPost>

    // ===== Following =====
    @GET("me/following")
    suspend fun getMyFollowing(@Query("email") email: String): List<FollowUser>

    // ===== AI =====
    @POST("ai/ask")
    suspend fun aiAsk(@Body req: AiAskReq): AiAskRes

    // ===== My Posts（MainActivity / MapEditorActivity 用）=====
    @GET("me/posts")
    suspend fun getMyPosts(@Query("email") email: String): List<MyPostRes>

    @POST("me/posts")
    suspend fun createMyPost(@Body req: CreatePostReq): CreatePostRes

    @GET("posts/{postId}")
    suspend fun getPostDetail(@Path("postId") postId: String): PostDetailRes

    @PUT("me/posts/{postId}")
    suspend fun updateMyPost(
        @Path("postId") postId: String,
        @Body req: UpdatePostReq
    ): OkRes

    @GET("me/posts/recommended")
    suspend fun getMyRecommendedPost(@Query("email") email: String): RecommendedPostRes?

    @DELETE("me/posts/{postId}")
    suspend fun deleteMyPost(
        @Path("postId") postId: String,
        @Query("email") email: String
    ): OkRes

    // ===== Spots（MapEditorActivity 用）=====
    @GET("posts/{postId}/spots")
    suspend fun getSpots(@Path("postId") postId: String): List<SpotRes>

    @POST("posts/{postId}/spots")
    suspend fun createSpot(
        @Path("postId") postId: String,
        @Body req: CreateSpotReq
    ): CreateSpotRes

    @PUT("posts/{postId}/spots/{spotId}")
    suspend fun updateSpot(
        @Path("postId") postId: String,
        @Path("spotId") spotId: String,
        @Body req: UpdateSpotReq
    ): OkRes

    @DELETE("posts/{postId}/spots/{spotId}")
    suspend fun deleteSpot(
        @Path("postId") postId: String,
        @Path("spotId") spotId: String,
        @Query("email") email: String
    ): OkRes

    // ===== Spot Photo（MapEditorActivity 用）=====
    @Multipart
    @POST("posts/{postId}/spots/{spotId}/photo")
    suspend fun uploadSpotPhoto(
        @Path("postId") postId: String,
        @Path("spotId") spotId: String,
        @Part("email") email: RequestBody,
        @Part photo: MultipartBody.Part
    ): OkRes

    // ===== Trips =====
    @GET("me/trips")
    suspend fun getMyTrips(@Query("email") email: String): List<TripRes>

    @POST("me/trips")
    suspend fun createTrip(@Body req: CreateTripReq): CreateTripRes

    @PUT("me/trips/{tripId}/title")
    suspend fun renameTrip(
        @Path("tripId") tripId: String,
        @Body req: RenameTripReq
    ): OkRes

    @PUT("me/trips/{tripId}/dates")
    suspend fun changeTripDates(
        @Path("tripId") tripId: String,
        @Body req: ChangeTripDatesReq
    ): OkRes

    @DELETE("me/trips/{tripId}")
    suspend fun deleteTrip(
        @Path("tripId") tripId: String,
        @Query("email") email: String
    ): OkRes

    @POST("auth/register")
    suspend fun register(@Body req: RegisterReq): OkRes

    @GET("users/{email}/public")
    suspend fun getUserPublicProfile(
        @Path("email") email: String
    ): PublicUserProfileRes

    @GET("users/{email}/posts")
    suspend fun getUserPostsPublic(
        @Path("email") email: String,
        @Query("limit") limit: Int = 300
    ): List<PublicPostRes>

    @GET("me/trips/{tripId}/days/{day}/stops")
    suspend fun getTripDayStops(
        @Path("tripId") tripId: String,
        @Path("day") day: Int,
        @Query("email") email: String
    ): List<TripStopRes>

    @POST("me/trips/{tripId}/days/{day}/stops/{stopId}/ai")
    suspend fun generateStopAi(
        @Path("tripId") tripId: String,
        @Path("day") day: Int,
        @Path("stopId") stopId: String,
        @Query("email") email: String
    ): AiTextRes

    @DELETE("trips/{tripId}/days/{day}/stops/{stopId}")
    suspend fun deleteTripStop(
        @Path("tripId") tripId: String,
        @Path("day") day: Int,
        @Path("stopId") stopId: String,
        @Query("email") email: String
    )

    @POST("me/trips/{tripId}/days/{day}/stops")
    suspend fun addTripStop(
        @Path("tripId") tripId: String,
        @Path("day") day: Int,
        @Query("email") email: String,
        @Body body: AddTripStopReq
    )

    @POST("ai/voice")
    suspend fun aiVoice(@Body req: AiVoiceReq): AiVoiceRes





}
