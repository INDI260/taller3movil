package com.example.taller3.Models

data class Usuario(
    val nombre: String = "",
    val apellido: String = "",
    val email: String = "",
    val id: String = "",
    val fotoPerfilUrl: String = "",
    val latitud: Double = 0.0,
    val longitud: Double = 0.0,
    val disponible: Boolean = false
)
