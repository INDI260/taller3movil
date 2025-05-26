package com.example.taller3.Mapas

import android.app.UiModeManager
import android.content.Context
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
import com.example.taller3.R
import com.example.taller3.databinding.ActivityDisponibleBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.*

class DisponibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisponibleBinding
    private lateinit var map: MapView

    private lateinit var userID: String
    private var userName: String? = null
    private lateinit var posicion: GeoPoint
    private lateinit var posicion2: GeoPoint
    private var currentLocationMarker: Marker? = null
    private var marcador: Marker? = null
    private lateinit var geocoder: Geocoder
    private var ultimaPosicion: GeoPoint? = null

    private val RADIUS_OF_EARTH_KM = 6378

    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var askedPermissionAlready = false
    private var askedToEnableGps = false

    private val locationSettings = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        if (it.resultCode == RESULT_OK) startLocationUpdates()
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (it) locationSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDisponibleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ajuste para el notch
        ViewCompat.setOnApplyWindowInsetsListener(binding.banner) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            insets
        }

        userID = intent.getStringExtra("usuarioID").orEmpty()
        userName = intent.getStringExtra("usuarioNombre")
        binding.nombreUsuarioBanner.text = userName ?: "Usuario"

        Configuration.getInstance().load(this, androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))
        geocoder = Geocoder(baseContext)
        map = binding.osmMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        locationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = createLocationRequest()
        locationCallback = createLocationCallback()

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermissionAlready) {
                locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                askedPermissionAlready = true
            }
        }

        posicionFirestore()

        binding.back.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        val uims = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uims.nightMode == UiModeManager.MODE_NIGHT_YES) {
            map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        if (!askedToEnableGps && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            askedToEnableGps = true
            locationSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        map.onPause()
    }

    private fun locationSettings() {
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(builder.build())
            .addOnSuccessListener { startLocationUpdates() }
            .addOnFailureListener {
                if (it is ResolvableApiException) {
                    val isr = IntentSenderRequest.Builder(it.resolution).build()
                    locationSettings.launch(isr)
                }
            }
    }

    private fun createLocationRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(5000)
            .build()
    }

    private fun createLocationCallback(): LocationCallback {
        return object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val newPosition = GeoPoint(loc.latitude, loc.longitude)

                if (!::posicion.isInitialized || newPosition != posicion) {
                    posicion = newPosition
                    addLocationMarker()
                    Firebase.auth.currentUser?.uid?.let {
                        FirebaseFirestore.getInstance().collection("usuarios").document(it)
                            .update("latitud", posicion.latitude, "longitud", posicion.longitude)
                    }
                }

                if (::posicion2.isInitialized) {
                    binding.distancia.text = "📍 Distancia hasta ti: %.2f Km".format(
                        distance(posicion.latitude, posicion.longitude, posicion2.latitude, posicion2.longitude)
                    )
                    ajustarVistaMapa(posicion, posicion2)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun stopLocationUpdates() {
        locationClient.removeLocationUpdates(locationCallback)
    }

    private fun addMarker(p: GeoPoint, snippet: String) {
        marcador?.let { map.overlays.remove(it) }
        marcador = Marker(map).apply {
            title = snippet
            subDescription = "Posición del usuario seleccionado"
            position = p
            icon = resources.getDrawable(R.drawable.baseline_arrow_drop_down_24, theme)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(marcador)
    }

    private fun addLocationMarker() {
        currentLocationMarker?.let { map.overlays.remove(it) }

        val direccion = geocoder.getFromLocation(posicion.latitude, posicion.longitude, 1)
            ?.firstOrNull()?.getAddressLine(0) ?: "Dirección desconocida"

        val marker = Marker(map).apply {
            title = direccion
            subDescription = "Lat: ${posicion.latitude}, Lon: ${posicion.longitude}"
            position = posicion
            icon = resources.getDrawable(R.drawable.baseline_accessibility_new_24, theme)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        map.overlays.add(marker)
        currentLocationMarker = marker
    }

    private fun posicionFirestore() {
        FirebaseFirestore.getInstance()
            .collection("usuarios")
            .whereEqualTo("id", userID)
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                for (document in snapshots) {
                    val lat = document.getDouble("latitud")
                    val lng = document.getDouble("longitud")
                    val nombre = document.getString("nombre") ?: "Usuario"

                    if (lat != null && lng != null) {
                        posicion2 = GeoPoint(lat, lng)

                        if (ultimaPosicion == null || ultimaPosicion != posicion2) {
                            addMarker(posicion2, "Usuario $nombre")
                            ultimaPosicion = posicion2

                            if (::posicion.isInitialized) {
                                binding.distancia.text = "📍 Distancia hasta ti: %.2f Km".format(
                                    distance(posicion.latitude, posicion.longitude, posicion2.latitude, posicion2.longitude)
                                )
                                ajustarVistaMapa(posicion, posicion2)
                            }
                        }
                    }
                }
            }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return RADIUS_OF_EARTH_KM * c
    }

    private fun ajustarVistaMapa(p1: GeoPoint, p2: GeoPoint) {
        val padding = 0.09 // Margen adicional
        val latMin = min(p1.latitude, p2.latitude) - padding
        val latMax = max(p1.latitude, p2.latitude) + padding
        val lonMin = min(p1.longitude, p2.longitude) - padding
        val lonMax = max(p1.longitude, p2.longitude) + padding

        val boundingBox = BoundingBox(latMax, lonMax, latMin, lonMin)
        map.zoomToBoundingBox(boundingBox, true)
    }
}
