package pipeline.validators

/**
 * Documented placeholder for the deferred dynamic canary described in the
 * implementation plan (§4, "Dynamic Canary Validation").
 *
 * The canary compiles a synthetic class against `custom-quals.jar` containing
 * a deliberately invalid subtyping assignment; a non-trivial qualifier
 * hierarchy must produce at least one compiler error. Dynamic checker
 * execution is deferred to future work, so this validator is intentionally
 * unwired: [supported] always returns false and [validate] always throws.
 */
object CanaryValidator {

    /** Dynamic canary validation is deferred to future work. */
    fun supported(): Boolean = false

    /**
     * Always throws [UnsupportedOperationException]: dynamic canary validation
     * is deferred to future work and must not be invoked by any stage.
     */
    fun validate(@Suppress("UNUSED_PARAMETER") vararg args: Any?): Unit {
        throw UnsupportedOperationException(
            "Dynamic canary validation is deferred to future work " +
                "(implementation plan §4.2): no synthetic canary compilation " +
                "is performed in the MVP."
        )
    }
}
