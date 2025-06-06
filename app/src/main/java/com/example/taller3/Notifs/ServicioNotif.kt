package com.example.taller3.Notifs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.taller3.Mapas.DisponibleActivity
import com.example.taller3.MenuAccountActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class ServicioNotif : Service() {

    private val CHANNEL_ID = "DisponibilidadUsuarios"
    private val CHANNEL_NAME = "Disponibilidad de usuarios"
    private val FOREGROUND_ID = 1
    private var listenerRegistration: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ServicioNotif", ">>> ServicioNotif.onCreate(): Arrancando servicio y listener de disponibilidad.")

        crearCanalNotificaciones()

        val notifForeground = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.taller3.R.drawable.hombre)
            .setContentTitle("Servicio activo")
            .setContentText("Escuchando cambios de disponibilidad")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(FOREGROUND_ID, notifForeground)
        registrarListenerDisponibilidad()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ServicioNotif", ">>> ServicioNotif.onDestroy(): Cerrando listener.")
        listenerRegistration?.remove()
    }

    private fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones cuando cambia la disponibilidad"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun registrarListenerDisponibilidad() {
        val db = FirebaseFirestore.getInstance()
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser == null) {
            Log.e("ServicioNotif", ">>> Usuario no autenticado. No se inicializa listener.")
            return
        } else {
            Log.d("ServicioNotif", ">>> Listener Firestore iniciado para UID: ${currentUser.uid}")
        }

        listenerRegistration = db.collection("usuarios")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("ServicioNotif", "Error al escuchar cambios", error)
                    return@addSnapshotListener
                }
                if (snapshots == null) {
                    Log.e("ServicioNotif", ">>> snapshots == null en el listener. No hay datos.")
                    return@addSnapshotListener
                }

                for (change in snapshots.documentChanges) {
                    if (change.type == DocumentChange.Type.MODIFIED) {
                        val doc = change.document
                        val userId = doc.id
                        val nombre = doc.getString("nombre")
                        val disponible = doc.getBoolean("disponible")

                        Log.d(
                            "ServicioNotif",
                            ">>> Cambio Firestore: docId=$userId disponible=$disponible nombre=$nombre"
                        )

                        // No notificar al propio usuario
                        if (currentUser.uid == userId) {
                            Log.d(
                                "ServicioNotif",
                                ">>> Cambio detectado es del usuario actual ($userId), se ignora."
                            )
                            continue
                        }

                        // Armamos el mensaje:
                        val mensaje = when {
                            nombre != null && disponible == true -> "$nombre está disponible"
                            nombre != null && disponible == false -> "$nombre ya no está disponible"
                            else -> "Usuario cambió su estado"
                        }

                        // Decidir a qué Activity vamos y qué extras enviamos:
                        val destinoClass: Class<*>
                        val extraUserId: String?
                        val extraUserName: String?

                        if (disponible == true) {
                            destinoClass = DisponibleActivity::class.java
                            extraUserId = userId
                            extraUserName = nombre ?: ""
                        } else {
                            destinoClass = MenuAccountActivity::class.java
                            extraUserId = null
                            extraUserName = null
                        }

                        Log.d(
                            "ServicioNotif",
                            ">>> Lanzando notificación. destinoClass=${destinoClass.simpleName} extraUserId=$extraUserId extraUserName=$extraUserName"
                        )

                        // Construir la notificación con los extras correspondientes
                        val notif = buildNotification(
                            "Cambio de disponibilidad",
                            mensaje,
                            com.example.taller3.R.drawable.hombre,
                            destinoClass,
                            extraUserId,
                            extraUserName
                        )

                        // VERIFICAR PERMISO ANTES DE NOTIFICAR (Android 13+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                NotificationManagerCompat.from(this)
                                    .notify(userId.hashCode(), notif)
                                Log.d("ServicioNotif", ">>> Notificación enviada (Android 13+)")
                            } else {
                                Log.w(
                                    "ServicioNotif",
                                    "No se envía notificación: permiso POST_NOTIFICATIONS denegado"
                                )
                            }
                        } else {
                            NotificationManagerCompat.from(this)
                                .notify(userId.hashCode(), notif)
                            Log.d("ServicioNotif", ">>> Notificación enviada (Android <= 12)")
                        }
                    }
                }
            }
    }

    private fun buildNotification(
        title: String,
        message: String,
        iconRes: Int,
        destino: Class<*>,
        extraUserId: String?,
        extraUserName: String?
    ): Notification {
        val intent = Intent(this, destino).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            extraUserId?.let { putExtra("usuarioID", it) }
            extraUserName?.let { putExtra("usuarioNombre", it) }
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    }
}
