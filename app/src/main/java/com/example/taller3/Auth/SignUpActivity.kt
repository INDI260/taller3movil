package com.example.taller3.Auth

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.taller3.BuildConfig
import com.example.taller3.ManejadorImagenes
import com.example.taller3.Models.Usuario
import com.example.taller3.databinding.ActivitySignUpBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.internal.wait
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var locationClient: FusedLocationProviderClient
    private var latitud: Double = 0.0
    private var longitud: Double = 0.0

    private var uriImagenPerfil: Uri? = null
    private lateinit var urlImagenPerfil: String
    private var archivoImagen: File? = null

    private lateinit var launcherGaleria: ActivityResultLauncher<String>
    private lateinit var launcherCamara: ActivityResultLauncher<Uri>

    private val permisoGaleria = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    private val permisoCamara = Manifest.permission.CAMERA
    private val permisoUbicacion = Manifest.permission.ACCESS_FINE_LOCATION

    private var intentosGaleria = 0
    private var intentosCamara = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        locationClient = LocationServices.getFusedLocationProviderClient(this)

        configurarLaunchers()
        configurarEventos()
        obtenerUbicacion()
    }

    private fun configurarLaunchers() {
        launcherGaleria = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                uriImagenPerfil = guardarImagenLocal(it)
                mostrarImagen(uriImagenPerfil!!)
                Toast.makeText(this, "Imagen cargada", Toast.LENGTH_SHORT).show()
            }
        }

        launcherCamara = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && archivoImagen?.exists() == true) {
                uriImagenPerfil = FileProvider.getUriForFile(this, "$packageName.fileprovider", archivoImagen!!)
                mostrarImagen(uriImagenPerfil!!)
                Toast.makeText(this, "Foto tomada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Foto cancelada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun configurarEventos() {
        binding.btnUploadGaleria.setOnClickListener {
            manejarPermisoGaleria()
        }

        binding.btnUploadDocCamara.setOnClickListener {
            manejarPermisoCamara()
        }

        binding.btnSignUp.setOnClickListener {
            val datos = recolectarDatos() ?: return@setOnClickListener

            if (uriImagenPerfil == null) {
                Toast.makeText(this, "Debes subir una imagen", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(datos.first.email, datos.second)
                .addOnSuccessListener {

                    ManejadorImagenes.subirImagen(baseContext, BuildConfig.IMG_API_KEY, uriImagenPerfil!!) { success, message ->
                        if (success) {

                            val url = JSONObject(message).getJSONObject("data").getString("url")
                            urlImagenPerfil = url
                            Log.i("subirImagen", urlImagenPerfil)

                            val uid = auth.currentUser!!.uid
                            val usuarioFinal = Usuario(
                                nombre = datos.first.nombre,
                                apellido = datos.first.apellido,
                                email = datos.first.email,
                                id = uid,
                                fotoPerfilUrl = urlImagenPerfil,
                                latitud = latitud,
                                longitud = longitud,
                                disponible = false
                            )

                            db.collection("usuarios").document(uid).set(usuarioFinal)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Cuenta creada 🎉", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(this, "Error en Firestore", Toast.LENGTH_SHORT).show()
                                }

                        } else {
                            Log.i("subirImagen", message.toString())
                        }
                    }


                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error al registrar", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun obtenerUbicacion() {
        if (ContextCompat.checkSelfPermission(this, permisoUbicacion) == PackageManager.PERMISSION_GRANTED) {
            locationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    latitud = it.latitude
                    longitud = it.longitude
                    Toast.makeText(this, "Ubicación obtenida", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(this, "Ubicación no disponible", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            requestPermissions(arrayOf(permisoUbicacion), 102)
        }
    }

    private fun manejarPermisoGaleria() {
        if (ContextCompat.checkSelfPermission(this, permisoGaleria) == PackageManager.PERMISSION_GRANTED) {
            launcherGaleria.launch("image/*")
        } else {
            intentosGaleria++
            if (intentosGaleria >= 3) {
                Toast.makeText(this, "Activa el permiso de galería en Ajustes", Toast.LENGTH_LONG).show()
            } else {
                requestPermissions(arrayOf(permisoGaleria), 100)
            }
        }
    }

    private fun manejarPermisoCamara() {
        if (ContextCompat.checkSelfPermission(this, permisoCamara) == PackageManager.PERMISSION_GRANTED) {
            abrirCamara()
        } else {
            intentosCamara++
            if (intentosCamara >= 3) {
                Toast.makeText(this, "Activa el permiso de cámara en Ajustes", Toast.LENGTH_LONG).show()
            } else {
                requestPermissions(arrayOf(permisoCamara), 101)
            }
        }
    }

    private fun abrirCamara() {
        archivoImagen = File(getExternalFilesDir(null), "foto_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", archivoImagen!!)
        launcherCamara.launch(uri)
    }

    private fun mostrarImagen(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            binding.imgPreview.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Error mostrando imagen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guardarImagenLocal(uri: Uri): Uri? {
        return try {
            val input = contentResolver.openInputStream(uri)
            val file = File(getExternalFilesDir(null), "perfil_${System.currentTimeMillis()}.jpg")
            val output = FileOutputStream(file)
            input?.copyTo(output)
            input?.close()
            output.close()
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }

    private fun recolectarDatos(): Pair<Usuario, String>? {
        val nombre = binding.etNombre.text.toString().trim()
        val apellido = binding.etApellido.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmar = binding.etConfirmPassword.text.toString().trim()

        return when {
            nombre.split(" ").size > 2 -> {
                Toast.makeText(this, "Máx. 2 nombres", Toast.LENGTH_SHORT).show(); null
            }
            apellido.split(" ").size > 2 -> {
                Toast.makeText(this, "Máx. 2 apellidos", Toast.LENGTH_SHORT).show(); null
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                Toast.makeText(this, "Correo inválido", Toast.LENGTH_SHORT).show(); null
            }
            password.length < 6 || password != confirmar -> {
                Toast.makeText(this, "Contraseña inválida o no coinciden", Toast.LENGTH_SHORT).show(); null
            }
            else -> Pair(Usuario(nombre, apellido, email),password)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, resultados)
        if (resultados.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
            return
        }

        when (requestCode) {
            100 -> launcherGaleria.launch("image/*")
            101 -> abrirCamara()
            102 -> obtenerUbicacion()
        }
    }
}
