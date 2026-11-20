package io.github.reihlinstinct.reporter.municipal

import android.content.res.AssetManager
import android.util.JsonReader
import android.util.JsonToken

/** Two bounded passes over the immutable APK asset: verify bytes, then stream address rows. */
object BundledStreetDirectory {
    fun load(assets: AssetManager): StreetDirectory {
        return load { assets.open("addresses.json") }
    }

    fun load(open: () -> java.io.InputStream): StreetDirectory {
        open().use { AddressLookupPin.verifyPinned(it) }
        return JsonReader(open().bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            requireName(reader, "version")
            if (reader.nextInt() != 1) throw MunicipalInputException("Unsupported address lookup format.")
            requireName(reader, "streets")
            val streets = mutableListOf<Any?>()
            reader.beginArray()
            while (reader.hasNext()) streets.add(readValue(reader))
            reader.endArray()
            requireName(reader, "addresses")
            reader.beginArray()
            val rows = sequence<Any?> {
                while (reader.hasNext()) yield(readValue(reader))
            }
            val directory = StreetDirectory.loadRows(streets, rows)
            reader.endArray()
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw MunicipalInputException("Trailing data in JSON.")
            directory
        }
    }

    private fun requireName(reader: JsonReader, expected: String) {
        if (reader.nextName() != expected)
            throw MunicipalInputException("Unsupported bundled address lookup format.")
    }

    /** Only a street or address row is retained, never the full address array or source text. */
    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val row = linkedMapOf<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) row[reader.nextName()] = readValue(reader)
            reader.endObject()
            row
        }
        JsonToken.BEGIN_ARRAY -> {
            val row = mutableListOf<Any?>()
            reader.beginArray()
            while (reader.hasNext()) row.add(readValue(reader))
            reader.endArray()
            row
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> throw MunicipalInputException("Unsupported address lookup format.")
    }
}
