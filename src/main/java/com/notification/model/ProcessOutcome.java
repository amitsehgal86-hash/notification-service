package com.notification.model;

/**
 * Result of running a notification through {@code NotificationOrchestrator}.
 * Used by the demo/load endpoint to classify outcomes for its summary.
 */
public enum ProcessOutcome {
    SENT,
    SUPPRESSED,   // blocked by the 3-day contact rule
    HELD,         // outside FDCPA 8am-9pm window; rescheduled
    OPTED_OUT,    // consumer opted out
    FAILED        // missing consumer, missing mini-Miranda, or provider failure
}
