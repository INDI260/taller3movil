package com.example.taller3.Mapas

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.taller3.MenuAccountActivity
import com.example.taller3.R
import com.example.taller3.databinding.ActivityDisponibleBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.ktx.Firebase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.*

// *********************************************************
//  DisponibleActivity completo, con manejo seguro de MapView
// *********************************************************
class DisponibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisponibleBinding
    private var map: MapView? = null               // 1️⃣ ahora es anulable

    private lateinit var userID: String
    private var userName: String? = null
    private lateinit var posicion: GeoPoint
    private lateinit var posicion2: GeoPoint
    private var currentLocationMarker: Marker? = null
    private var marcador: Marker? = null
    private lateinit var geocoder: Geocoder
    private var ultimaPosicion: GeoPoint? = null

    private val RADIUS_OF_EARTH_KM = 6378.0

    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var askedPermissionAlready = false
    private var askedToEnableGps = false

    // Variables para ajustar la vista una sola vez o si cambia
    private var primeraAjustada = false
    private var ultimaCaja: BoundingBox? = null
    private val EPS = 1e-5

    // Listener de Firestore para poder cancelarlo
    private var registroFirestore: ListenerRegistration? = null

    /* ------------- PERMISOS Y AJUSTES DE GPS ------------- */

    private val locationSettings = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { if (it.resultCode == RESULT_OK) startLocationUpdates() }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) locationSettings() }

    /* ---------------------- onCreate ---------------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDisponibleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Extras
        userID   = intent.getStringExtra("usuarioID").orEmpty()
        userName = intent.getStringExtra("usuarioNombre")
        binding.nombreUsuarioBanner.text = userName ?: "Usuario"

        // Ajuste para notch / barra de estado
        ViewCompat.setOnApplyWindowInsetsListener(binding.banner) { v, insets ->
            v.setPadding(
                v.paddingLeft,
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).top,
                v.paddingRight, v.paddingBottom
            )
            insets
        }

        // Configuración OSM y utilidades
        Configuration.getInstance().load(
            this, androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        )
        geocoder = Geocoder(this)

        map = binding.osmMap
        map?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }

        // Localización
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = createLocationRequest()
        locationCallback = createLocationCallback()

        if (ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && !askedPermissionAlready
        ) {
            askedPermissionAlready = true
            locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

        iniciarListenerFirestore()

        /* Botón back */
        binding.back.setOnClickListener {
            if (isTaskRoot) {
                startActivity(Intent(this, MenuAccountActivity::class.java))
            } else finish()
        }
    }

    /* ---------------- CICLO DE VIDA MAPA ---------------- */

    override fun onResume() {
        super.onResume()
        map?.onResume()

        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).let { uims ->
            if (uims.nightMode == UiModeManager.MODE_NIGHT_YES) {
                map?.overlayManager?.tilesOverlay
                    ?.setColorFilter(TilesOverlay.INVERT_COLORS)
            }
        }
        if (!askedToEnableGps && ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            askedToEnableGps = true
            locationSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        map?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        registroFirestore?.remove()
        registroFirestore = null
        map = null                       // liberamos referencia
    }

    /* -------------- LOCALIZACIÓN Y GPS ----------------- */

    private fun createLocationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(5_000)
            .build()

    private fun locationSettings() {
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(builder.build())
            .addOnSuccessListener { startLocationUpdates() }
            .addOnFailureListener {
                if (it is ResolvableApiException) {
                    val isr = IntentSenderRequest.Builder(it.resolution).build()
                    locationSettings.launch(isr)
                }
            }
    }

    private fun createLocationCallback(): LocationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                val nuevaPos = GeoPoint(loc.latitude, loc.longitude)

                if (!::posicion.isInitialized || nuevaPos != posicion) {
                    posicion = nuevaPos
                    addLocationMarker()

                    Firebase.auth.currentUser?.uid?.let { uid ->
                        FirebaseFirestore.getInstance()
                            .collection("usuarios").document(uid)
                            .update("latitud", posicion.latitude,
                                "longitud", posicion.longitude)
                    }
                }

                if (::posicion2.isInitialized) {
                    binding.distancia.text = "📍 Distancia hasta ti: %.2f Km".format(
                        distance(posicion.latitude, posicion.longitude,
                            posicion2.latitude, posicion2.longitude)
                    )
                    ajustarVistaMapa(posicion, posicion2)
                }
            }
        }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        }
    }

    private fun stopLocationUpdates() =
        locationClient.removeLocationUpdates(locationCallback)

    /* ----------------- FIRESTORE LISTENER --------------- */

    private fun iniciarListenerFirestore() {
        registroFirestore = FirebaseFirestore.getInstance()
            .collection("usuarios")
            .whereEqualTo("id", userID)
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null || map == null) return@addSnapshotListener

                for (doc in snapshots) {
                    val lat = doc.getDouble("latitud") ?: continue
                    val lng = doc.getDouble("longitud") ?: continue
                    val nombre = doc.getString("nombre") ?: "Usuario"

                    val nueva = GeoPoint(lat, lng)
                    if (ultimaPosicion == nueva) continue   // no cambió

                    posicion2 = nueva
                    ultimaPosicion = nueva

                    addMarker(nueva, "Usuario $nombre")

                    if (::posicion.isInitialized) {
                        binding.distancia.text = "📍 Distancia hasta ti: %.2f Km".format(
                            distance(posicion.latitude, posicion.longitude,
                                nueva.latitude, nueva.longitude)
                        )
                        ajustarVistaMapa(posicion, nueva)
                    }
                }
            }
    }

    /* -------------------- MARCADORES -------------------- */

    private fun addMarker(p: GeoPoint, snippet: String) {
        val mapa = map ?: return
        runOnUiThread {
            marcador?.let { mapa.overlays.remove(it) }
            marcador = Marker(mapa).apply {
                title = snippet
                subDescription = "Posición del usuario seleccionado"
                position = p
                icon = getDrawable(R.drawable.baseline_arrow_drop_down_24)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapa.overlays.add(marcador)
            mapa.invalidate()
        }
    }

    private fun addLocationMarker() {
        val mapa = map ?: return
        runOnUiThread {
            currentLocationMarker?.let { mapa.overlays.remove(it) }

            val dir = geocoder.getFromLocation(
                posicion.latitude, posicion.longitude, 1
            )?.firstOrNull()?.getAddressLine(0) ?: "Dirección desconocida"

            currentLocationMarker = Marker(mapa).apply {
                title = dir
                subDescription = "Lat: ${posicion.latitude}, Lon: ${posicion.longitude}"
                position = posicion
                icon = getDrawable(R.drawable.baseline_accessibility_new_24)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapa.overlays.add(currentLocationMarker)
            mapa.invalidate()
        }
    }

    /* --------------- AJUSTAR VISTA MAPA ----------------- */

    private fun casiIgual(a: Double, b: Double, eps: Double = EPS) =
        abs(a - b) < eps

    private fun ajustarVistaMapa(p1: GeoPoint, p2: GeoPoint) {
        val mapa = map ?: return

        val padding = 0.09
        val latMin = min(p1.latitude, p2.latitude) - padding
        val latMax = max(p1.latitude, p2.latitude) + padding
        val lonMin = min(p1.longitude, p2.longitude) - padding
        val lonMax = max(p1.longitude, p2.longitude) + padding
        val nuevaCaja = BoundingBox(latMax, lonMax, latMin, lonMin)

        val hayQueAjustar = when {
            !primeraAjustada -> true
            ultimaCaja == null -> true
            else -> !casiIgual(nuevaCaja.latNorth, ultimaCaja!!.latNorth) ||
                    !casiIgual(nuevaCaja.latSouth, ultimaCaja!!.latSouth) ||
                    !casiIgual(nuevaCaja.lonEast,  ultimaCaja!!.lonEast)  ||
                    !casiIgual(nuevaCaja.lonWest,  ultimaCaja!!.lonWest)
        }

        if (hayQueAjustar) {
            mapa.zoomToBoundingBox(nuevaCaja, true)
            primeraAjustada = true
            ultimaCaja = nuevaCaja
        }
    }

    /* --------------------- DISTANCIA -------------------- */

    private fun distance(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return RADIUS_OF_EARTH_KM * c
    }
}
