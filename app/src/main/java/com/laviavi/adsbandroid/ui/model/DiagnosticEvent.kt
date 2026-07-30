package com.laviavi.adsbandroid.ui.model

data class DiagnosticEvent(
    val id: Long,
    val timestampMs: Long,
    val category: EventCategory,
    val severity: Severity,
    val message: String,
    val detail: String? = null,
)

enum class EventCategory { OPERATIONAL, SOURCE, DECODER, LOCATION, APP_ERROR }
enum class Severity { INFO, WARNING, ERROR }

class DiagnosticEventBuffer(private val maxSize: Int = 2000) {
    private val events = ArrayDeque<DiagnosticEvent>(maxSize)
    private var nextId = 1L

    fun add(category: EventCategory, severity: Severity, message: String, detail: String? = null): DiagnosticEvent {
        val event = DiagnosticEvent(
            id = nextId++,
            timestampMs = System.currentTimeMillis(),
            category = category,
            severity = severity,
            message = message,
            detail = detail,
        )
        if (events.size >= maxSize) events.removeFirst()
        events.addLast(event)
        return event
    }

    fun snapshot(): List<DiagnosticEvent> = events.toList()
    fun errorCount(): Int = events.count { it.severity == Severity.ERROR }
    fun clear() { events.clear() }
}
