package com.notification.domain;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Mustache-style renderer: replaces {@code {{key}}} tokens with variable values.
 * The mini-Miranda text lives hardcoded in the template body (never a variable), so it always
 * survives rendering and can be validated afterwards.
 */
@Component
public class TemplateEngine {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    public String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return "";
        }
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
