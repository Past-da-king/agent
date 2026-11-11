package com.past9.phoneaos.people

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Importing the phone's contacts (GitHub issue #13, made opt-in on purpose): the permission is in the manifest, and it
 * is asked for only at the moment the person taps Import on the People screen, never at app start. Only names are
 * read, nothing else, and only names that are not already on the list are added.
 */
object ContactsImport {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Every distinct contact name on the phone, sorted. Empty when the permission has not been given. */
    fun names(context: Context): List<String> {
        if (!allowed(context)) return emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)?.trim().orEmpty()
                    if (n.isNotEmpty()) out += n
                }
            }
        }
        return out.toList()
    }

    /** Add the names not on the list yet. Returns how many were added. */
    suspend fun into(context: Context, repo: PeopleRepository): Int {
        val have = repo.people().map { it.name.trim().lowercase() }.toSet()
        return names(context).filter { it.lowercase() !in have }.take(500).count { name ->
            runCatching { repo.add(name); true }.getOrDefault(false)
        }
    }
}
