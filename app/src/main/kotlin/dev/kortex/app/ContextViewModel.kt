package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.ambient.ContactRef
import dev.kortex.core.ambient.Conversation
import dev.kortex.core.ambient.Entity
import dev.kortex.core.ambient.MemoryEntry
import dev.kortex.core.store.toDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextUi(
    val contacts: List<ContactRef> = emptyList(),
    val selectedContactId: String? = null,
    val conversation: Conversation? = null,
    val memories: List<MemoryEntry> = emptyList(),
    val entities: List<Entity> = emptyList(),
)

class ContextViewModel(app: Application) : AndroidViewModel(app) {
    private val container get() = getApplication<KortexApp>().container

    private val _ui = MutableStateFlow(ContextUi())
    val ui: StateFlow<ContextUi> = _ui.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val contacts = container.contactDao.all().map { it.toDomain() }
        val currentId = _ui.value.selectedContactId ?: contacts.firstOrNull()?.id
        
        _ui.update { it.copy(contacts = contacts, selectedContactId = currentId) }
        loadContext(currentId)
    }

    fun onContactSelected(contactId: String) {
        _ui.update { it.copy(selectedContactId = contactId) }
        viewModelScope.launch { loadContext(contactId) }
    }

    private suspend fun loadContext(contactId: String?) {
        if (contactId == null) return
        
        val conversation = container.conversationDao.forContact(contactId)?.toDomain()
        val memories = container.memoryDao.forContact(contactId).map { it.toDomain() }
        
        // Find entities via relations from this contact's conversation
        val conversationId = "conv_$contactId"
        val entityIds = container.relationDao.from("CONVERSATION", conversationId)
            .map { it.toId }
        
        val entities = entityIds.mapNotNull { id ->
            container.graphEntityDao.byId(id)?.toDomain()
        }
        
        _ui.update { 
            it.copy(
                conversation = conversation,
                memories = memories,
                entities = entities
            )
        }
    }
}
