package io.github.reihlinstinct.reporter.ledger

import io.github.reihlinstinct.reporter.ledger.LedgerRestartPlan.Schema
import io.github.reihlinstinct.reporter.ledger.LedgerRestartPlan.Journal
import io.github.reihlinstinct.reporter.ledger.LedgerRestartPlan.Origin
import io.github.reihlinstinct.reporter.ledger.LedgerRestartPlan.Custody
import io.github.reihlinstinct.reporter.ledger.LedgerRestartPlan.Facts
import io.github.reihlinstinct.reporter.ledger.LedgerRestartPlan.Step
import org.junit.Assert.*
import org.junit.Test

class LedgerRestartPlanTest {
    private fun facts(schema: Schema, journal: Journal = Journal.ABSENT,
        origin: Origin = Origin.NONE, custody: Custody = Custody.COMMITTED) =
        Facts(schema, journal, origin, custody)

    @Test fun knownRestartRowsMatchReviewedTableAndAllOtherCombinationsStop() {
        val table = mapOf(
            facts(Schema.ABSENT, custody = Custody.ABSENT) to Step.VALIDATE_FRESH_ABSENCE_AND_CREATE_CUSTODY,
            facts(Schema.ABSENT) to Step.WRITE_FRESH_CREATING_THEN_CREATE,
            facts(Schema.V1, custody = Custody.ABSENT) to Step.PROVE_V1_THEN_CREATE_CUSTODY_AND_MIGRATE,
            facts(Schema.V1) to Step.PROVE_V1_WITH_EXISTING_CUSTODY_AND_MIGRATE,
            facts(Schema.V1, Journal.MIGRATING, Origin.MIGRATED) to Step.PROVE_V1_WITH_EXISTING_CUSTODY_AND_MIGRATE,
            facts(Schema.V2, Journal.MIGRATING, Origin.MIGRATED) to Step.WRITE_CLEANING_THEN_VERIFY_AND_CLEAN,
            facts(Schema.V2, Journal.CLEANING, Origin.MIGRATED) to Step.VERIFY_V2_THEN_RESUME_CLEANING,
            facts(Schema.ABSENT, Journal.FRESH_CREATING, Origin.FRESH) to Step.WRITE_FRESH_CREATING_THEN_CREATE,
            facts(Schema.UNINITIALIZED, Journal.FRESH_CREATING, Origin.FRESH) to Step.OPEN_FRESH_VERIFY_EMPTY_V2_THEN_COMPLETE,
            facts(Schema.V2, Journal.FRESH_CREATING, Origin.FRESH) to Step.OPEN_FRESH_VERIFY_EMPTY_V2_THEN_COMPLETE,
            facts(Schema.V2, Journal.COMPLETE, Origin.FRESH) to Step.AUTHENTICATED_V2_OPEN,
            facts(Schema.V2, Journal.COMPLETE, Origin.MIGRATED) to Step.AUTHENTICATED_V2_OPEN,
        )
        assertEquals(12, table.size)
        for (schema in Schema.values()) for (journal in Journal.values())
            for (origin in Origin.values()) for (custody in Custody.values()) {
                val input = Facts(schema, journal, origin, custody)
                assertEquals(input.toString(), table[input] ?: Step.STOP_RETAIN_ALL, LedgerRestartPlan.next(input))
            }
    }

    @Test fun missingCompleteDatabaseNeverRecreatesEvenForFreshOrigin() {
        for (origin in listOf(Origin.FRESH, Origin.MIGRATED))
            for (schema in listOf(Schema.ABSENT, Schema.UNINITIALIZED, Schema.V1, Schema.OTHER_OR_UNREADABLE))
                assertEquals(Step.STOP_RETAIN_ALL,
                    LedgerRestartPlan.next(facts(schema, Journal.COMPLETE, origin)))
    }

    @Test fun missingCustodyWithAnyJournalStopsNotMigratesOrResets() {
        for (schema in Schema.values()) for (journal in Journal.values())
            for (origin in Origin.values()) {
                if (journal == Journal.ABSENT) continue
                assertEquals(Step.STOP_RETAIN_ALL,
                    LedgerRestartPlan.next(facts(schema, journal, origin, Custody.ABSENT)))
            }
    }

    @Test fun encryptedSchemaWithoutJournalCannotBeReclassifiedAsFreshOrPlaintext() {
        for (custody in Custody.values()) assertEquals(Step.STOP_RETAIN_ALL,
            LedgerRestartPlan.next(facts(Schema.V2, custody = custody)))
    }
}
