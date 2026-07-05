package com.notification;

import com.notification.domain.MiniMirandaValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMirandaValidatorTest {

    private final MiniMirandaValidator validator = new MiniMirandaValidator();

    @Test
    void bothPhrasesPresent() {
        String body = "This is an attempt to collect a debt and any information obtained will be used "
                + "for that purpose.";
        assertTrue(validator.isValid(body));
    }

    @Test
    void caseInsensitiveMatch() {
        String body = "THIS IS AN ATTEMPT TO COLLECT A DEBT. ANY INFORMATION OBTAINED WILL BE USED FOR "
                + "THAT PURPOSE.";
        assertTrue(validator.isValid(body));
    }

    @Test
    void onePhraseMissing() {
        String body = "This is an attempt to collect a debt.";
        assertFalse(validator.isValid(body));
    }

    @Test
    void bothPhrasesMissing() {
        assertFalse(validator.isValid("Your account balance is $100."));
    }

    @Test
    void nullBodyIsInvalid() {
        assertFalse(validator.isValid(null));
    }
}
