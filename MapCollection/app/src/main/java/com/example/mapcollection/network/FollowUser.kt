package com.example.mapcollection.network

data class FollowUser(
    val email: String = "",
    val userName: String = "",
    val introduction: String = "",
    val photoUrl: String? = null
)
