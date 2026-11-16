package io.github.reihlinstinct.reporter.core

/**
 * Issue #424: the built-in description templates and the shipped mapping from city categories to them.
 * The Hebrew text is the owner-approved wording (2026-11-13 06:13, "מאשר את הניסוחים"). Templates are keyed by stable
 * tags. City codes appear only in [shippedMapping], which the user's own mapping overrides.
 */
object BuiltInTemplates {
    const val RED_WHITE_CURB = "red_white_curb"
    const val SIDEWALK_PARKING = "sidewalk_parking"
    const val DISABLED_BAY = "disabled_bay"
    const val GENERIC = "generic"

    private fun t(v: String): TemplatePart = TemplatePart.Text(v)
    private fun s(slot: TemplateSlot): TemplatePart = TemplatePart.Slot(slot)

    /** "רכב {vehicle}, " appears only when the registry lookup returned a vehicle. */
    private val vehicleLead = TemplatePart.Optional(listOf(t("רכב "), s(TemplateSlot.VEHICLE), t(", ")))

    /** "ברחוב {street} {house} " drops with the street; the house number drops alone. */
    private val where = TemplatePart.Optional(
        listOf(t("ברחוב "), s(TemplateSlot.STREET), TemplatePart.Optional(listOf(t(" "), s(TemplateSlot.HOUSE))), t(" ")),
    )

    private fun mainSentences(middle: String, request: String): List<TemplateSentence> = listOf(
        TemplateSentence("where", listOf(vehicleLead, t("מספר רישוי "), s(TemplateSlot.PLATE), t(", $middle "), where, t("בירושלים."))),
        TemplateSentence("request", listOf(t(request))),
    )

    /** The ids of the sentences shared by every template that depend on verified facts. */
    val stateSentenceIds = setOf("no_disabled_permit", "licence_expired")

    /** Sentences added to every template by state. Each is a removable chip: deleting it keeps it deleted. */
    private val stateSentences = listOf(
        TemplateSentence(
            "no_disabled_permit",
            listOf(t("לפי הנתונים הפומביים של משרד התחבורה לא נמצא תו חניה לנכה על מספר הרישוי הזה.")),
            onlyWhenPermit = PermitState.CHECKED_NONE,
        ),
        TemplateSentence(
            "licence_expired",
            listOf(
                t("לפי נתוני משרד התחבורה, תוקף רישיון הרכב הרשום הסתיים ביום "), s(TemplateSlot.LICENSE_EXPIRY),
                TemplatePart.Optional(listOf(t(" והמבחן האחרון הרשום נערך ביום "), s(TemplateSlot.LAST_TEST))), t("."),
            ),
        ),
    )

    val templates: List<DescriptionTemplate> = listOf(
        DescriptionTemplate(RED_WHITE_CURB, 1, mainSentences("חונה בצמוד לאבן שפה המסומנת באדום-לבן", "אבקש לשלוח פקח לבדיקת החניה האסורה ולאכיפה בהתאם לדין.") + stateSentences),
        DescriptionTemplate(SIDEWALK_PARKING, 1, mainSentences("חונה על המדרכה", "אבקש לשלוח פקח לבדיקת החניה האסורה ולאכיפה בהתאם לדין.") + stateSentences),
        DescriptionTemplate(DISABLED_BAY, 1, mainSentences("חונה בחניית נכים", "אבקש לשלוח פקח לבדוק אם קיים תו חניה לנכה בתוקף ולאכוף בהתאם לדין.") + stateSentences),
        DescriptionTemplate(GENERIC, 1, mainSentences("חונה", "אבקש לשלוח פקח לבדיקה ולאכיפה בהתאם לדין.") + stateSentences),
    )

    fun byTag(tag: String): DescriptionTemplate? = templates.firstOrNull { it.tag == tag }

    /** City code first, the recorded name beside it so a renamed category is flagged. Codes and names from the bundled taxonomy snapshot assets/taxonomy/municipal_subjects.json. */
    val shippedMapping: List<TemplateMappingEntry> = listOf(
        TemplateMappingEntry("1144", "חנייה אסורה- אדום לבן / אין עצירה", RED_WHITE_CURB),
        TemplateMappingEntry("1145", "חנייה אסורה - חנייה על מדרכה", SIDEWALK_PARKING),
        TemplateMappingEntry("1153", "חנייה אסורה - חניית רכב במקום המיועד לנכה", DISABLED_BAY),
    )
}
