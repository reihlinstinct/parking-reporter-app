package io.github.reihlinstinct.reporter.ledger

/** Pure inactive restart table from LEDGER_ENCRYPTION.md.
 * Not an opener, proof, journal codec, authorization or recovery implementation.
 * A future locked factory must establish facts from authenticated/read-back
 * sources and independently validate the schema/source proof before effects.
 * These values cannot be used as permission to create custody or rewrite data. */
object LedgerRestartPlan {
    enum class Schema { ABSENT, UNINITIALIZED, V1, V2, OTHER_OR_UNREADABLE }
    enum class Journal { ABSENT, FRESH_CREATING, MIGRATING, CLEANING, COMPLETE, INVALID }
    enum class Origin { NONE, FRESH, MIGRATED }
    enum class Custody { ABSENT, COMMITTED, INVALID }
    enum class Step {
        STOP_RETAIN_ALL,
        VALIDATE_FRESH_ABSENCE_AND_CREATE_CUSTODY,
        WRITE_FRESH_CREATING_THEN_CREATE,
        OPEN_FRESH_VERIFY_EMPTY_V2_THEN_COMPLETE,
        PROVE_V1_THEN_CREATE_CUSTODY_AND_MIGRATE,
        PROVE_V1_WITH_EXISTING_CUSTODY_AND_MIGRATE,
        WRITE_CLEANING_THEN_VERIFY_AND_CLEAN,
        VERIFY_V2_THEN_RESUME_CLEANING,
        AUTHENTICATED_V2_OPEN,
    }

    /** No timestamps or inferred source identity: contradictory facts stop. */
    data class Facts(val schema: Schema, val journal: Journal,
        val origin: Origin, val custody: Custody)

    fun next(facts: Facts): Step = with(facts) {
        if (custody == Custody.INVALID || journal == Journal.INVALID ||
            schema == Schema.OTHER_OR_UNREADABLE) return Step.STOP_RETAIN_ALL
        val originAgrees = when (journal) {
            Journal.ABSENT -> origin == Origin.NONE
            Journal.FRESH_CREATING -> origin == Origin.FRESH
            Journal.MIGRATING, Journal.CLEANING -> origin == Origin.MIGRATED
            Journal.COMPLETE -> origin == Origin.FRESH || origin == Origin.MIGRATED
            Journal.INVALID -> false
        }
        if (!originAgrees) return Step.STOP_RETAIN_ALL
        if (journal != Journal.ABSENT && custody != Custody.COMMITTED)
            return Step.STOP_RETAIN_ALL
        when (journal) {
            Journal.ABSENT -> when (schema) {
                Schema.ABSENT -> if (custody == Custody.ABSENT)
                    Step.VALIDATE_FRESH_ABSENCE_AND_CREATE_CUSTODY
                    else Step.WRITE_FRESH_CREATING_THEN_CREATE
                Schema.V1 -> if (custody == Custody.ABSENT)
                    Step.PROVE_V1_THEN_CREATE_CUSTODY_AND_MIGRATE
                    else Step.PROVE_V1_WITH_EXISTING_CUSTODY_AND_MIGRATE
                else -> Step.STOP_RETAIN_ALL
            }
            Journal.FRESH_CREATING -> when (schema) {
                Schema.ABSENT -> Step.WRITE_FRESH_CREATING_THEN_CREATE
                Schema.UNINITIALIZED, Schema.V2 -> Step.OPEN_FRESH_VERIFY_EMPTY_V2_THEN_COMPLETE
                else -> Step.STOP_RETAIN_ALL
            }
            Journal.MIGRATING -> when (schema) {
                Schema.V1 -> Step.PROVE_V1_WITH_EXISTING_CUSTODY_AND_MIGRATE
                Schema.V2 -> Step.WRITE_CLEANING_THEN_VERIFY_AND_CLEAN
                else -> Step.STOP_RETAIN_ALL
            }
            Journal.CLEANING -> if (schema == Schema.V2)
                Step.VERIFY_V2_THEN_RESUME_CLEANING else Step.STOP_RETAIN_ALL
            Journal.COMPLETE -> if (schema == Schema.V2)
                Step.AUTHENTICATED_V2_OPEN else Step.STOP_RETAIN_ALL
            Journal.INVALID -> Step.STOP_RETAIN_ALL
        }
    }
}
