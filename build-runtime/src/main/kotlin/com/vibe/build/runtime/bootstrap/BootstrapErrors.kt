package com.vibe.build.runtime.bootstrap

/** Base class for all bootstrap subsystem exceptions. */
sealed class BootstrapException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class ManifestException(message: String, cause: Throwable? = null) :
    BootstrapException(message, cause)

class SignatureMismatchException(message: String, cause: Throwable? = null) :
    BootstrapException(message, cause)

class HashMismatchException(expected: String, actual: String) :
    BootstrapException("SHA-256 mismatch: expected=$expected actual=$actual")

class DownloadFailedException(message: String, cause: Throwable? = null) :
    BootstrapException(message, cause)

class ExtractionFailedException(message: String, cause: Throwable? = null) :
    BootstrapException(message, cause)
