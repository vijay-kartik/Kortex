package dev.kortex.app

import android.content.Context
import android.provider.ContactsContract
import dev.kortex.core.ambient.ContactAffinity
import dev.kortex.core.ambient.ContactRef
import dev.kortex.core.ambient.Handle
import dev.kortex.core.ambient.HandleType
import dev.kortex.core.store.ContactDao
import dev.kortex.core.store.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds the canonical contact registry from the device contact book (requires READ_CONTACTS).
 * Each saved contact becomes a [ContactRef] with its phone + email handles, which is what the
 * IdentityResolver matches incoming signals against. Starred contacts are marked FAVORITE.
 */
class ContactSeeder(
    private val context: Context,
    private val contacts: ContactDao,
) {
    private class Builder(val id: String) {
        var name: String = ""
        var starred: Boolean = false
        val phones = mutableSetOf<String>()
        val emails = mutableSetOf<String>()
    }

    /** Reads device contacts and upserts them. Returns how many were written. */
    suspend fun seedFromDevice(): Int = withContext(Dispatchers.IO) {
        val byId = linkedMapOf<String, Builder>()
        readPhones(byId)
        readEmails(byId)

        byId.values.forEach { b ->
            val handles = b.phones.map { Handle(HandleType.PHONE, it) } +
                b.emails.map { Handle(HandleType.EMAIL, it) }
            val contact = ContactRef(
                id = b.id,
                displayName = b.name.ifBlank { b.phones.firstOrNull() ?: "Unknown" },
                handles = handles,
                affinity = if (b.starred) ContactAffinity.FAVORITE else ContactAffinity.OTHER,
            )
            contacts.upsert(contact.toEntity())
        }
        byId.size
    }

    private fun readPhones(byId: MutableMap<String, Builder>) {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val starIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
            while (c.moveToNext()) {
                val id = c.getString(idIdx) ?: continue
                val b = byId.getOrPut(id) { Builder(id) }
                b.name = c.getString(nameIdx).orEmpty().ifBlank { b.name }
                if (c.getInt(starIdx) == 1) b.starred = true
                c.getString(numIdx)?.takeIf { it.isNotBlank() }?.let { b.phones += it }
            }
        }
    }

    private fun readEmails(byId: MutableMap<String, Builder>) {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val addrIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                val id = c.getString(idIdx) ?: continue
                val b = byId.getOrPut(id) { Builder(id) }
                if (b.name.isBlank()) b.name = c.getString(nameIdx).orEmpty()
                c.getString(addrIdx)?.takeIf { it.isNotBlank() }?.let { b.emails += it }
            }
        }
    }
}
