package com.example.taller3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location

import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

import androidx.core.content.ContextCompat

import com.bumptech.glide.Glide
import com.example.taller3.Auth.LoginActivity
import com.example.taller3.Mapas.LocationsActivity
import com.example.taller3.Notifs.ServicioNotif
import com.example.taller3.databinding.ActivityMenuAccountBinding
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MenuAccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMenuAccountBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val REQUEST_NOTIF_PERMISSION = 1001

    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("MenuAccountActivity", ">>> Permiso POST_NOTIFICATIONS CONCEDIDO. Arrancando ServicioNotif.")
                startServicioNotif()
            } else {
                Log.d("MenuAccountActivity", ">>> Permiso POST_NOTIFICATIONS NO concedido. Solicitando permiso.")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIF_PERMISSION
                )
            }
        } else {
            startServicioNotif()
        }


        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "No hay usuario autenticado", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val uid = user.uid
        cargarDatosUsuario(uid)

        binding.btnCerrarSesion.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        binding.btnVerUsuariosDisponibles.setOnClickListener {
            startActivity(Intent(this, UsuariosDisponiblesActivity::class.java))
        }

        binding.back.setOnClickListener {
            startActivity(Intent(this, LocationsActivity::class.java))
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val servicio = Intent(this, ServicioNotif::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, servicio)
            } else {
                startService(servicio)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIF_PERMISSION
            )
        }

        binding.btnDisponible.setOnClickListener {
            val nuevoEstado = !binding.btnDisponible.isSelected
            val uid = auth.currentUser?.uid ?: return@setOnClickListener
            db.collection("usuarios")
                .document(uid)
                .update("disponible", nuevoEstado)
                .addOnSuccessListener {
                    binding.btnDisponible.isSelected = nuevoEstado
                    binding.btnDisponible.text = if (nuevoEstado) "Disponible" else "No disponible"
                    Toast.makeText(this, if (nuevoEstado) "Ahora estás disponible" else "Ya no estás disponible", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error al cambiar disponibilidad", Toast.LENGTH_SHORT).show()
                }
        }

        startService(Intent(this, ServicioNotif::class.java))

        // ---------------------------
        // Configurar ubicación en tiempo real
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(5000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val uid = auth.currentUser?.uid ?: return

                db.collection("usuarios").document(uid)
                    .update("latitud", loc.latitude, "longitud", loc.longitude)
                    .addOnFailureListener {
                        Log.e("MenuAccount", "Error al actualizar ubicación", it)
                    }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationClient.removeLocationUpdates(locationCallback)
    }

    fun cargarDatosUsuario(uid: String) {
        db.collection("usuarios").document(uid).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val nombre = document.getString("nombre") ?: ""
                    val apellido = document.getString("apellido") ?: ""
                    val correo = document.getString("email") ?: ""
                    val lat = document.getDouble("latitud") ?: 0.0
                    val lng = document.getDouble("longitud") ?: 0.0
                    val fotoUrl = document.getString("fotoPerfilUrl") ?: ""

                    binding.txtNombre.text = "$nombre $apellido"
                    binding.txtCorreo.text = correo
                    binding.txtUbicacion.text = "Ubicación: $lat, $lng"

                    ManejadorImagenes.mostrarImagenDesdeUrl(fotoUrl, binding.imgPerfil, this)
                } else {
                    Toast.makeText(this, "Documento de usuario no encontrado", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al cargar datos del usuario", Toast.LENGTH_LONG).show()
                Log.e("MenuAccount", "Firestore error: ", it)
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MenuAccountActivity", ">>> Permiso POST_NOTIFICATIONS fue concedido en runtime. Arrancando ServicioNotif.")
                startServicioNotif()
            } else {
                Log.w("MenuAccountActivity", ">>> Permiso POST_NOTIFICATIONS DENEGADO.")
                Toast.makeText(this, "No recibirás notificaciones.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServicioNotif() {
        val intent = Intent(this, ServicioNotif::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        Log.d("MenuAccountActivity", ">>> startServicioNotif() llamado.")
    }
}
