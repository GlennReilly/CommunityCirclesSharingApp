package circles.sync

import org.w3c.dom.events.Event

/** Just the parts of the IndexedDB API the replica uses (nothing in the Kotlin stdlib declares it). */
internal external interface IdbRequest {
    val result: dynamic
    val error: dynamic
    var onsuccess: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
}

internal external interface IdbOpenRequest : IdbRequest {
    var onupgradeneeded: ((Event) -> Unit)?
    var onblocked: ((Event) -> Unit)?
}

internal external interface IdbFactory {
    fun open(name: String, version: Int): IdbOpenRequest
}

internal external interface IdbDatabase {
    val objectStoreNames: dynamic
    fun createObjectStore(name: String, options: dynamic): IdbObjectStore
    fun transaction(stores: Array<String>, mode: String): IdbTransaction
    fun close()
}

internal external interface IdbTransaction {
    val error: dynamic
    var oncomplete: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
    var onabort: ((Event) -> Unit)?
    fun objectStore(name: String): IdbObjectStore
}

internal external interface IdbObjectStore {
    fun put(value: dynamic): IdbRequest
    fun get(key: dynamic): IdbRequest
    fun getAll(range: dynamic): IdbRequest
    fun delete(key: dynamic): IdbRequest
    fun createIndex(name: String, keyPath: dynamic, options: dynamic): dynamic
    fun index(name: String): IdbIndex
}

internal external interface IdbIndex {
    fun getKey(key: dynamic): IdbRequest
}
