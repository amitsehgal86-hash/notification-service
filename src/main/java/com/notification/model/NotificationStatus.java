package com.notification.model;

public enum NotificationStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
    HELD,
    /** Dropped by the 3-day contact-suppression rule (this build's core rule). */
    SUPPRESSED
}
