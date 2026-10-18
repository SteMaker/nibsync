package com.stemaker.nibsync.storage

open class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CorruptDataException(message: String, cause: Throwable? = null) : StorageException(message, cause)

class UnsupportedFormatException(val version: Int) :
    StorageException("Document format version $version is newer than this app supports")

class DocumentNotFoundException(id: String) : StorageException("Document '$id' not found")
