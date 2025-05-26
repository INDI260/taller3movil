package com.example.taller3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.taller3.Mapas.DisponibleActivity
import com.example.taller3.Models.Usuario
import com.example.taller3.adapters.UsuarioDisponibleAdapter
import com.example.taller3.databinding.ActivityUsuariosDisponiblesBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class UsuariosDisponiblesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUsuariosDisponiblesBinding
    private lateinit var adapter: UsuarioDisponibleAdapter
    private val usuarios = mutableListOf<Usuario>()
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsuariosDisponiblesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        escucharUsuariosDisponibles()

        binding.back.setOnClickListener {
            finish() // volver a la actividad anterior correctamente
        }

        // Ajustar padding del banner para evitar notch
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.banner)) { view, insets ->
            view.setPadding(
                view.paddingLeft,
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
    }

    private fun setupRecyclerView() {
        adapter = UsuarioDisponibleAdapter(usuarios) { usuario ->
            Intent(this, DisponibleActivity::class.java).apply {
                putExtra("usuarioID", usuario.id)
                putExtra("usuarioNombre", usuario.nombre)
            }.also(::startActivity)
        }
        binding.listaUsuariosDisponibles.layoutManager = LinearLayoutManager(this)
        binding.listaUsuariosDisponibles.adapter = adapter
    }

    private fun escucharUsuariosDisponibles() {
        FirebaseFirestore.getInstance()
            .collection("usuarios")
            .whereEqualTo("disponible", true)
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) {
                    Toast.makeText(this, "Error al escuchar usuarios", Toast.LENGTH_SHORT).show()
                    Log.e("UsuariosDisponibles", "Firestore listener error: ", e)
                    return@addSnapshotListener
                }

                usuarios.clear()
                for (document in snapshots) {
                    if (document.id != auth.currentUser?.uid) {
                        val usuario = document.toObject(Usuario::class.java)
                        usuarios.add(usuario)
                    }
                }
                adapter.notifyDataSetChanged()
            }
    }
}
