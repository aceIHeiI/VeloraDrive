package com.iheii.veloradrive

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Datos de cada persona: su celular y su coche. Nada fijo en el código. */
data class Profile(val deviceName: String?, val carBrand: String?, val carModel: String?)

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "profile")

/**
 * Perfil guardado en Preferences DataStore ("device_name", "car_brand", "car_model"). Sobrevive a
 * cerrar la app y se cambia desde Ajustes sin recompilar. La pantalla principal solo muestra esto.
 */
object ProfileStore {
    private val DEVICE_NAME = stringPreferencesKey("device_name")
    private val CAR_BRAND = stringPreferencesKey("car_brand")
    private val CAR_MODEL = stringPreferencesKey("car_model")

    /** Marcas para elegir en Ajustes; "Otro" deja escribir cualquier marca. */
    val BRANDS = listOf("Nissan", "Toyota", "Honda", "Chevrolet", "Volkswagen", "Kia", "Hyundai")

    fun flow(context: Context): Flow<Profile> = context.profileStore.data.map { p ->
        Profile(
            deviceName = p[DEVICE_NAME]?.takeIf { it.isNotBlank() },
            // Instalaciones anteriores guardaban la marca en SharedPreferences: se respeta.
            carBrand = p[CAR_BRAND]?.takeIf { it.isNotBlank() } ?: CockpitPrefs.legacyBrand(context),
            carModel = p[CAR_MODEL]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun save(context: Context, deviceName: String, carBrand: String, carModel: String) {
        context.profileStore.edit { p ->
            p[DEVICE_NAME] = deviceName.trim()
            p[CAR_BRAND] = carBrand.trim()
            p[CAR_MODEL] = carModel.trim()
        }
    }
}
