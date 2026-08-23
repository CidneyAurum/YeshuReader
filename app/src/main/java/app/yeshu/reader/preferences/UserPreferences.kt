package app.yeshu.reader.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.yeshuPreferences by preferencesDataStore(name = "yeshu_preferences")

/** Non-secret UI preferences. API keys intentionally live only in Android Keystore. */
class UserPreferences(private val context: Context) {
    val themeMode: Flow<String> = context.yeshuPreferences.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { it[THEME_MODE] ?: "system" }

    val showIllustrations: Flow<Boolean> = context.yeshuPreferences.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { it[SHOW_ILLUSTRATIONS] ?: true }

    suspend fun setThemeMode(value: String) {
        context.yeshuPreferences.edit { it[THEME_MODE] = value.takeIf { mode -> mode in MODES } ?: "system" }
    }

    suspend fun setShowIllustrations(value: Boolean) {
        context.yeshuPreferences.edit { it[SHOW_ILLUSTRATIONS] = value }
    }

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val SHOW_ILLUSTRATIONS = booleanPreferencesKey("show_illustrations")
        private val MODES = setOf("system", "light", "dark")
    }
}
