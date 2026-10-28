package io.github.strongsand.lshell.beacon

/** UI and the short-lived manual sync service share one repository, not two sync engines. */
internal class BeaconRepositoryLease<T : Any>(
    private val busy: (T) -> Boolean,
    private val dispose: (T) -> Unit
) {
    private var value: T? = null
    private var owners = 0

    @Synchronized fun acquire(create: () -> T): T = (value ?: create().also { value = it }).also { owners++ }

    @Synchronized fun release(instance: T) {
        check(instance === value && owners > 0)
        owners--
        closeIfUnused(instance)
    }

    // A pending service start retains the repository even if the Activity is already destroyed.
    @Synchronized fun closeIfUnused(instance: T) {
        if (instance === value && owners == 0 && !busy(instance)) {
            value = null
            dispose(instance)
        }
    }
}
