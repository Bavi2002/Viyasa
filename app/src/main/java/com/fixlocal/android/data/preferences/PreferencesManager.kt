package com.fixlocal.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fixlocal.android.data.model.FixTone
import com.fixlocal.android.data.model.InferenceBackend
import com.fixlocal.android.data.model.ModelOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fixlocal_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_TONE = stringPreferencesKey("selected_tone")
        private val KEY_BACKEND = stringPreferencesKey("inference_backend")
        private val KEY_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_CUSTOM_INSTRUCTION = stringPreferencesKey("custom_tone_instruction")
        private val KEY_WORD_LIMIT_ENABLED = booleanPreferencesKey("word_limit_enabled")
        private val KEY_WORD_LIMIT_COUNT = intPreferencesKey("word_limit_count")
        private val KEY_TRANSLATE_ENABLED = booleanPreferencesKey("feature_translate_enabled")
        private val KEY_SUMMARIZE_ENABLED = booleanPreferencesKey("feature_summarize_enabled")
        private val KEY_CONTINUE_ENABLED = booleanPreferencesKey("feature_continue_enabled")
        private val KEY_TRANSLATE_TARGET = stringPreferencesKey("translate_target_language")
        private val KEY_SUMMARIZE_FORMAT = stringPreferencesKey("summarize_format")
    }

    val isTranslateEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_TRANSLATE_ENABLED] ?: true
    }

    suspend fun setTranslateEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TRANSLATE_ENABLED] = enabled
        }
    }

    val isSummarizeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SUMMARIZE_ENABLED] ?: true
    }

    suspend fun setSummarizeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SUMMARIZE_ENABLED] = enabled
        }
    }

    val isContinueWritingEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_CONTINUE_ENABLED] ?: true
    }

    suspend fun setContinueWritingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CONTINUE_ENABLED] = enabled
        }
    }

    val defaultTranslateTargetFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_TRANSLATE_TARGET] ?: "English"
    }

    suspend fun setDefaultTranslateTarget(language: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TRANSLATE_TARGET] = language
        }
    }

    val defaultSummarizeFormatFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_SUMMARIZE_FORMAT] ?: "Bullets"
    }

    suspend fun setDefaultSummarizeFormat(format: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SUMMARIZE_FORMAT] = format
        }
    }

    val selectedToneFlow: Flow<FixTone> = context.dataStore.data.map { preferences ->
        val name = preferences[KEY_TONE]
        FixTone.fromName(name)
    }

    suspend fun setTone(tone: FixTone) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TONE] = tone.name
        }
    }

    val selectedBackendFlow: Flow<InferenceBackend> = context.dataStore.data.map { preferences ->
        val name = preferences[KEY_BACKEND]
        if (name == InferenceBackend.GPU.name) {
            InferenceBackend.GPU
        } else {
            InferenceBackend.CPU
        }
    }

    suspend fun setBackend(backend: InferenceBackend) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BACKEND] = backend.name
        }
    }

    val selectedModelFlow: Flow<ModelOption> = context.dataStore.data.map { preferences ->
        val id = preferences[KEY_MODEL]
        ModelOption.entries.find { it.modelId == id } ?: ModelOption.GEMMA3_270M
    }

    suspend fun setSelectedModel(option: ModelOption) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MODEL] = option.modelId
        }
    }

    val customInstructionFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_CUSTOM_INSTRUCTION] ?: "Fix grammar and spelling while keeping it natural and concise."
    }

    suspend fun setCustomInstruction(instruction: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CUSTOM_INSTRUCTION] = instruction.trim()
        }
    }

    val wordLimitEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_WORD_LIMIT_ENABLED] ?: false
    }

    val wordLimitCountFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_WORD_LIMIT_COUNT] ?: 25
    }

    suspend fun setWordLimit(enabled: Boolean, limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WORD_LIMIT_ENABLED] = enabled
            preferences[KEY_WORD_LIMIT_COUNT] = limit.coerceIn(5, 200)
        }
    }
}
