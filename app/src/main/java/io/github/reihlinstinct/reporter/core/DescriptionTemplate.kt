package io.github.reihlinstinct.reporter.core

/** Issue #424: suggested description text, driven by form facts and keyed by stable tags, never by city codes. */
enum class TemplateSlot { PLATE, VEHICLE, STREET, HOUSE, LICENSE_EXPIRY, LAST_TEST }

/** Disabled-permit state of the vehicle. Only a checked result may drive wording. */
enum class PermitState { UNKNOWN, CHECKED_NONE, HAS_PERMIT }

/** Everything a template may read. A blank or missing slot counts as empty. */
data class TemplateFacts(val values: Map<TemplateSlot, String> = emptyMap(), val permit: PermitState = PermitState.UNKNOWN) {
    fun value(slot: TemplateSlot): String? = values[slot]?.trim()?.takeIf { it.isNotEmpty() }
}

sealed interface TemplatePart {
    data class Text(val value: String) : TemplatePart
    data class Slot(val slot: TemplateSlot) : TemplatePart
    /** Shown only when every slot inside has a value; otherwise only this group disappears. */
    data class Optional(val parts: List<TemplatePart>) : TemplatePart
}

/** A sentence is the unit that appears, disappears and is tracked as untouched. [onlyWhenPermit] is a required permit state. */
data class TemplateSentence(val id: String, val parts: List<TemplatePart>, val onlyWhenPermit: PermitState? = null)

data class DescriptionTemplate(val tag: String, val version: Int, val sentences: List<TemplateSentence>)

data class RenderedSentence(val id: String, val text: String)

object DescriptionTemplates {
    /** Null when a required slot is empty. */
    private fun renderParts(parts: List<TemplatePart>, facts: TemplateFacts): String? {
        val out = StringBuilder()
        for (part in parts) {
            when (part) {
                is TemplatePart.Text -> out.append(part.value)
                is TemplatePart.Slot -> out.append(facts.value(part.slot) ?: return null)
                is TemplatePart.Optional -> renderParts(part.parts, facts)?.let { out.append(it) }
            }
        }
        return out.toString()
    }

    /** Sentences with an empty required slot, or an unmet permit condition, are dropped whole. Never a placeholder. */
    fun render(template: DescriptionTemplate, facts: TemplateFacts): List<RenderedSentence> =
        template.sentences.mapNotNull { s ->
            if (s.onlyWhenPermit != null && s.onlyWhenPermit != facts.permit) return@mapNotNull null
            renderParts(s.parts, facts)?.trim()?.takeIf { it.isNotEmpty() }?.let { RenderedSentence(s.id, it) }
        }

    fun join(sentences: List<RenderedSentence>): String = sentences.joinToString(" ") { it.text }

    private sealed interface Seg { data class Gap(val text: String) : Seg; data class Sent(val id: String, val text: String) : Seg }

    /**
     * Brings [current] up to date after the form changed. An untouched description is simply re-rendered. Otherwise a
     * sentence that is still exactly as last rendered is replaced or removed in place (all positions are found on the
     * original text, in template order, so one replacement can never be matched by another); a sentence the user
     * edited is kept as written and never re-added; a sentence that is new is placed after its nearest preceding
     * template neighbour that is present (or before the nearest following one, or last). Text the template never
     * produced is left alone.
     */
    fun update(current: String, previous: List<RenderedSentence>, next: List<RenderedSentence>): String {
        if (isUntouched(current, previous)) return join(next)
        val touched = HashSet<String>()
        val segs = ArrayList<Seg>()
        var cursor = 0
        val nextById = next.associateBy { it.id }
        for (p in previous) {
            val at = current.indexOf(p.text, cursor)
            if (at < 0) { touched.add(p.id); continue }
            if (at > cursor) segs.add(Seg.Gap(current.substring(cursor, at)))
            nextById[p.id]?.let { segs.add(Seg.Sent(p.id, it.text)) }
            cursor = at + p.text.length
        }
        if (cursor < current.length) segs.add(Seg.Gap(current.substring(cursor)))
        val known = previous.map { it.id }.toSet()
        next.forEachIndexed { i, n ->
            if (n.id in known || n.id in touched) return@forEachIndexed
            fun indexOfSent(id: String) = segs.indexOfFirst { it is Seg.Sent && it.id == id }
            val before = (i - 1 downTo 0).map { indexOfSent(next[it].id) }.firstOrNull { it >= 0 }
            val after = (i + 1 until next.size).map { indexOfSent(next[it].id) }.firstOrNull { it >= 0 }
            val piece = Seg.Sent(n.id, n.text)
            when {
                before != null -> segs.add(before + 1, Seg.Gap(" ")).also { segs.add(before + 2, piece) }
                after != null -> segs.add(after, piece).also { segs.add(after + 1, Seg.Gap(" ")) }
                else -> { segs.add(Seg.Gap(" ")); segs.add(piece) }
            }
        }
        val out = segs.joinToString("") { when (it) { is Seg.Gap -> it.text; is Seg.Sent -> it.text } }
        return out.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** The description is untouched when it is blank or exactly what was last rendered. */
    fun isUntouched(current: String, previous: List<RenderedSentence>): Boolean =
        current.isBlank() || current.trim() == join(previous)
}

/** Where a city category's template comes from. */
data class TemplateMappingEntry(val code: String, val name: String?, val tag: String)

sealed interface TemplateResolution {
    data class Match(val tag: String) : TemplateResolution
    /** The code still exists but the city renamed it: flagged for the user, never applied silently. */
    data class NeedsReview(val entry: TemplateMappingEntry, val liveName: String) : TemplateResolution
    object None : TemplateResolution
}

object TemplateMapping {
    private fun norm(s: String): String =
        s.replace(Regex("[\u05BE\u2010-\u2015\u2212]"), "-").replace(Regex("[\"'\u05F3\u05F4\u201C\u201D\u2018\u2019]"), "").replace(Regex("\\s+"), " ").trim().lowercase()

    /**
     * The user's entries win over the shipped ones. The city code is tried first; a matching code whose recorded
     * name differs from the live name needs review. With no code match, the normalised name is the fallback.
     * With nothing found, [fallbackTag] (a generic template) applies, or nothing.
     */
    fun resolve(
        topic: MunicipalSubject?,
        user: List<TemplateMappingEntry>,
        shipped: List<TemplateMappingEntry>,
        fallbackTag: String? = null,
    ): TemplateResolution {
        if (topic == null) return TemplateResolution.None
        for (entries in listOf(user, shipped)) {
            entries.firstOrNull { it.code == topic.code }?.let { e ->
                return if (e.name != null && norm(e.name) != norm(topic.name)) TemplateResolution.NeedsReview(e, topic.name)
                else TemplateResolution.Match(e.tag)
            }
            entries.firstOrNull { it.name != null && norm(it.name) == norm(topic.name) }?.let { return TemplateResolution.Match(it.tag) }
        }
        return fallbackTag?.let { TemplateResolution.Match(it) } ?: TemplateResolution.None
    }
}
