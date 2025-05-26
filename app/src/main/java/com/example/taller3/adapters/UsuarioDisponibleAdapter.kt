package com.example.taller3.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.taller3.ManejadorImagenes
import com.example.taller3.Models.Usuario
import com.example.taller3.databinding.ItemUsuarioDisponibleBinding

class UsuarioDisponibleAdapter(
    private var usuarios: MutableList<Usuario>,
    private val onItemClick: (Usuario) -> Unit
) : RecyclerView.Adapter<UsuarioDisponibleAdapter.UsuarioDisponibleViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsuarioDisponibleViewHolder {
        val binding = ItemUsuarioDisponibleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsuarioDisponibleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsuarioDisponibleViewHolder, position: Int) {
        holder.bind(usuarios[position])
    }

    override fun getItemCount(): Int = usuarios.size

    inner class UsuarioDisponibleViewHolder(private val binding: ItemUsuarioDisponibleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(usuario : Usuario) {
            binding.txtNombre.text  = usuario.nombre

            ManejadorImagenes.mostrarImagenDesdeUrl(usuario.fotoPerfilUrl, binding.imgUsuario, binding.root.context)
            // Evento de clic
            binding.ubicacion.setOnClickListener { onItemClick(usuario) }
        }
    }

    fun updateList(nuevos: List<Usuario>) {
        (usuarios).apply {
            clear()
            addAll(nuevos)
        }
        notifyDataSetChanged()
    }
}