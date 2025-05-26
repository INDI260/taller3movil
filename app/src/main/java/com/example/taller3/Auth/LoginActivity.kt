package com.example.taller3.Auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.taller3.Mapas.DisponibleActivity
import com.example.taller3.MenuAccountActivity
import com.example.taller3.R
import com.example.taller3.databinding.ActivityLoginBinding
import com.example.taller3.Mapas.LocationsActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var notifPermissionLauncher: ActivityResultLauncher<String>

    private val listaUsuarios = mutableListOf<Map<String, String>>()  // email, nombre, uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            startActivity(Intent(this, LocationsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notifPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, "Necesitamos notificaciones para mostrar cambios en usuarios", Toast.LENGTH_LONG).show()
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Toast.makeText(this, "Sin permiso, no verás cambios en la lista de usuarios", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnLogin.isEnabled = false

        binding.emailEditText.addTextChangedListener { validateFields() }
        binding.passwordEditText.addTextChangedListener { validateFields() }

        binding.passwordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && binding.btnLogin.isEnabled) {
                binding.btnLogin.performClick()
                true
            } else false
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.emailEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    val uid = auth.currentUser?.uid ?: return@addOnSuccessListener

                    val prefs = getSharedPreferences("usuarios_previos", MODE_PRIVATE)
                    val cuentas = prefs.getStringSet("emails", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    cuentas.add(email)
                    prefs.edit().putStringSet("emails", cuentas).apply()

                    db.collection("usuarios").document(uid).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                val nombre = doc.getString("nombre") ?: ""
                                val apellido = doc.getString("apellido") ?: ""
                                val uriString = doc.getString("fotoPerfilUrl") ?: ""

                                val userData = mapOf(
                                    "correo" to email,
                                    "nombre" to "$nombre $apellido",
                                    "foto" to uriString
                                )

                                prefs.edit().putString("usuario_$email", userData.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

                                Toast.makeText(this, "¡Bienvenido!", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this, LocationsActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                })
                            } else {
                                Toast.makeText(this, "Usuario autenticado pero no encontrado en Firestore", Toast.LENGTH_LONG).show()
                            }
                        }
                }
                .addOnFailureListener { ex ->
                    when (ex) {
                        is FirebaseAuthInvalidUserException ->
                            Toast.makeText(this, "Correo no registrado", Toast.LENGTH_LONG).show()
                        is FirebaseAuthInvalidCredentialsException ->
                            Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_LONG).show()
                        else -> {
                            Toast.makeText(this, "Error: ${ex.localizedMessage}", Toast.LENGTH_LONG).show()
                            Log.e("LoginActivity", "Firebase Auth error: ", ex)
                        }
                    }
                }
        }

        binding.btnSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        configurarRecyclerUsuarios()
    }

    private fun validateFields() {
        val emailValid = Patterns.EMAIL_ADDRESS.matcher(binding.emailEditText.text.toString().trim()).matches()
        val passwordValid = binding.passwordEditText.text.toString().trim().isNotEmpty()
        binding.btnLogin.isEnabled = emailValid && passwordValid
    }

    private fun configurarRecyclerUsuarios() {
        val prefs = getSharedPreferences("usuarios_previos", MODE_PRIVATE)
        val emails = prefs.getStringSet("emails", emptySet()) ?: emptySet()

        listaUsuarios.clear()

        emails.forEach { correo ->
            val raw = prefs.getString("usuario_$correo", null)
            if (raw != null) {
                val mapa = raw.split(";").associate {
                    val (k, v) = it.split("=")
                    k to v
                }
                listaUsuarios.add(mapa)
            }
        }

        binding.recyclerUsuarios.apply {
            layoutManager = LinearLayoutManager(this@LoginActivity)
            adapter = object : RecyclerView.Adapter<UsuarioViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsuarioViewHolder {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_usuario, parent, false)
                    return UsuarioViewHolder(view)
                }

                override fun onBindViewHolder(holder: UsuarioViewHolder, position: Int) {
                    val usuario = listaUsuarios[position]
                    holder.bind(usuario)
                }

                override fun getItemCount(): Int = listaUsuarios.size
            }
        }
    }

    inner class UsuarioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nombre: TextView = view.findViewById(R.id.txtNombre)
        private val correo: TextView = view.findViewById(R.id.txtCorreo)
        private val imagen: ImageView = view.findViewById(R.id.imgUsuario)

        fun bind(data: Map<String, String>) {
            nombre.text = data["nombre"]
            correo.text = data["correo"]

            val uri = try {
                Uri.parse(data["foto"])
            } catch (e: Exception) {
                null
            }

            if (uri != null && File(uri.path ?: "").exists()) {
                Glide.with(this@LoginActivity)
                    .load(uri)
                    .placeholder(R.drawable.ic_profile)
                    .into(imagen)
            } else {
                imagen.setImageResource(R.drawable.ic_profile)
            }

            itemView.setOnClickListener {
                binding.emailEditText.setText(data["correo"])
                Toast.makeText(this@LoginActivity, "Usuario cargado", Toast.LENGTH_SHORT).show()
            }
        }
    }
}