package com.vibe.build.runtime.bootstrap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and streams the current [BootstrapState] for the app.
 */
interface BootstrapStateStore {
    val state: Flow<BootstrapState>
    suspend fun update(state: BootstrapState)
    suspend fun current(): BootstrapState
}

/**
 * DataStore-backed impl. State is serialized as JSON string in a single
 * preferences key, keeping the schema simple while still supporting the
 * sealed-hierarchy structure. On first read (key missing) we return
 * [BootstrapState.NotInstalled].
 */
@Singleton
class DataStoreBootstrapStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : BootstrapStateStore {

    private val key = stringPreferencesKey(PREF_KEY)

    override val state: Flow<BootstrapState> = dataStore.data.map { prefs ->
        BootstrapStateJson.decode(prefs[key].orEmpty())
    }

    override suspend fun update(state: BootstrapState) {
        dataStore.edit { prefs ->
            prefs[key] = BootstrapStateJson.encode(state)
        }
    }

    override suspend fun current(): BootstrapState = state.first()

    private companion object {
        const val PREF_KEY = "bootstrap_state_json"
    }
}

/**
 * In-memory implementation used by JVM unit tests.
 */
class InMemoryBootstrapStateStore(
    initial: BootstrapState = BootstrapState.NotInstalled,
) : BootstrapStateStore {
    private val flow = MutableStateFlow(initial)

    override val state: Flow<BootstrapState> = flow.asStateFlow()

    override suspend fun update(state: BootstrapState) {
        flow.value = state
    }

    override suspend fun current(): BootstrapState = flow.value
}
