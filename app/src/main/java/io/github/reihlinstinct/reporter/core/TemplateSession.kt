package io.github.reihlinstinct.reporter.core

/**
 * Issue #424: keeps a report's description in step with the category and the form, without ever overwriting the
 * user's own words. Pure: the screen owns the state and the text, this decides the next text.
 *
 * A caller supplies [TemplateSlot.LICENSE_EXPIRY] only when the registry shows the licence expired, and sets
 * [PermitState.CHECKED_NONE] only after a completed lookup found no disabled permit: the sentences that depend on
 * those facts then appear, and nothing else makes them appear.
 */
object TemplateSession {
    /**
     * The draft with another category. [ReviewDraft.edit] clears the description when the subject changes. When
     * [userEditedText] (the user really typed in this screen) the text is kept for the template to judge, so it freezes
     * and is never replaced. Otherwise the old behaviour holds: the text is cleared and the template writes the new one,
     * so text that merely arrived with the draft (a reopened or received one) cannot carry a plate into a no-plate category.
     */
    fun withSubject(draft: ReviewDraft, subject: String, userEditedText: Boolean): ReviewDraft =
        draft.edit(subject = subject).let { if (userEditedText) it.copy(exactHebrewText = draft.exactHebrewText) else it }

    /** [frozen]: the user changed the text before the category changed, so this category's template is not applied. Then [rendered] holds only the state sentences still tracked. */
    data class State(val tag: String? = null, val rendered: List<RenderedSentence> = emptyList(), val frozen: Boolean = false)

    data class Result(val text: String, val state: State, val needsReview: TemplateResolution.NeedsReview? = null)

    fun apply(
        topic: MunicipalSubject?,
        userMapping: List<TemplateMappingEntry>,
        facts: TemplateFacts,
        currentText: String,
        state: State,
        shipped: List<TemplateMappingEntry> = BuiltInTemplates.shippedMapping,
    ): Result {
        val resolution = TemplateMapping.resolve(topic, userMapping, shipped, fallbackTag = BuiltInTemplates.GENERIC)
        val review = resolution as? TemplateResolution.NeedsReview
        val template = (resolution as? TemplateResolution.Match)?.let { BuiltInTemplates.byTag(it.tag) }
        if (template == null) {
            // No template for this category (or it needs review): the text stays as it is and the tracking is kept,
            // so a later category still sees an untouched template text as untouched.
            // The shared state sentences still follow the verified facts, each tracked on its own.
            val ids = BuiltInTemplates.stateSentenceIds
            val tracked = state.rendered.filter { it.id in ids }
            val stateNext = BuiltInTemplates.byTag(BuiltInTemplates.GENERIC)
                ?.let { DescriptionTemplates.render(it, facts) }.orEmpty().filter { it.id in ids }
            if (tracked.isEmpty() && stateNext.isEmpty()) return Result(currentText, state, review)
            val text = DescriptionTemplates.update(currentText, tracked, stateNext)
            return Result(text, state.copy(rendered = state.rendered.filter { it.id !in ids } + stateNext), review)
        }
        val next = DescriptionTemplates.render(template, facts)
        if (state.frozen && currentText.isNotBlank()) {
            // The user took over the text. Only the shared state sentences keep following the facts, each tracked on its
            // own, so a fact that is no longer true never stays and a verified one can still appear.
            val ids = BuiltInTemplates.stateSentenceIds
            val nextState = next.filter { it.id in ids }
            val text = DescriptionTemplates.update(currentText, state.rendered.filter { it.id in ids }, nextState)
            return Result(text, State(template.tag, nextState, frozen = true))
        }
        if (template.tag != state.tag) {
            return if (DescriptionTemplates.isUntouched(currentText, state.rendered)) {
                Result(DescriptionTemplates.join(next), State(template.tag, next))
            } else {
                Result(currentText, State(template.tag, state.rendered.filter { it.id in BuiltInTemplates.stateSentenceIds }, frozen = true))
            }
        }
        return Result(DescriptionTemplates.update(currentText, state.rendered, next), State(template.tag, next))
    }
}
