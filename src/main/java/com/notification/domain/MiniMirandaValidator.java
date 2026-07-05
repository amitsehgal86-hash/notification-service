package com.notification.domain;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FDCPA mini-Miranda check. Every outbound message must contain the required phrases.
 * Validated programmatically post-render — never trust the template author.
 */
@Component
public class MiniMirandaValidator {

    private static final List<String> REQUIRED_PHRASES = List.of(
            "attempt to collect a debt",
            "information obtained will be used for that purpose"
    );

    public boolean isValid(String messageBody) {
        if (messageBody == null) {
            return false;
        }
        String lower = messageBody.toLowerCase();
        return REQUIRED_PHRASES.stream().allMatch(lower::contains);
    }
}
