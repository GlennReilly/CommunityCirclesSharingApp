@file:JsModule("fake-indexeddb/auto")
@file:JsNonModule

package circles.sync

/** Importing this module installs an in-memory IndexedDB (and IDBKeyRange) as globals, for the Node tests. */
external val indexedDB: dynamic
