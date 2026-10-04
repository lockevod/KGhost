package com.enderthor.kghost.geo

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide lock serializing every whole-library mutation (import, model rebuild, reconcile sweep,
 * startup maintenance, ride-end tidy). Only the OUTER boundary of such a job takes it; helpers never do
 * (a Mutex is not reentrant, so nesting deadlocks).
 */
object LibraryLock {
    val mutex = Mutex()
}
