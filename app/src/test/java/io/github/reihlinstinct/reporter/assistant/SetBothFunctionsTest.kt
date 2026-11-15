package io.github.reihlinstinct.reporter.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** #390 item 3: a partial enable is rolled back; a partial disable never re-enables. */
class SetBothFunctionsTest {
    @Test fun failedSecondEnableTurnsTheFirstBackOff() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AssistantEntry.setBothFunctions(listOf("a", "b"), true) { id, on ->
                calls += id to on
                if (id == "b" && on) throw IllegalStateException("indexing lag")
            } }
        }
        assertEquals(listOf("a" to true, "b" to true, "a" to false), calls)
    }

    @Test fun failedDisableNeverReEnables() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AssistantEntry.setBothFunctions(listOf("a", "b"), false) { id, on ->
                calls += id to on
                if (id == "b") throw IllegalStateException("x")
            } }
        }
        assertEquals(listOf("a" to false, "b" to false), calls)
    }

    @Test fun successSetsBothInOrder() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        runBlocking { AssistantEntry.setBothFunctions(listOf("a", "b"), true) { id, on -> calls += id to on } }
        assertEquals(listOf("a" to true, "b" to true), calls)
    }
}
