package com.notification.domain;

import java.util.UUID;

/** Domain exceptions grouped in one file for brevity. */
public final class DomainExceptions {

    private DomainExceptions() {}

    public static class ConsumerNotFoundException extends RuntimeException {
        public ConsumerNotFoundException(UUID consumerId) {
            super("Consumer preferences not found: " + consumerId);
        }
    }

    public static class TemplateNotFoundException extends RuntimeException {
        public TemplateNotFoundException(UUID templateId) {
            super("Template not found: " + templateId);
        }
    }

    /** Thrown when a rendered message is missing FDCPA mini-Miranda language. */
    public static class MiniMirandaMissingException extends RuntimeException {
        public MiniMirandaMissingException(UUID templateId) {
            super("Rendered message missing mini-Miranda language; template=" + templateId);
        }
    }
}
