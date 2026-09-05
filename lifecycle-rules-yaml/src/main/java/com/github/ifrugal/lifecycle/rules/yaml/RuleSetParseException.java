package com.github.ifrugal.lifecycle.rules.yaml;

/**
 * A rule set body (or a file/resource holding one) could not be parsed into the document model: malformed
 * YAML/JSON, an unknown field, or a required field missing. Distinct from
 * {@link com.github.ifrugal.lifecycle.api.rules.RuleSetValidationException}, which is raised by the compiler
 * once a document is structurally valid but semantically wrong.
 */
public final class RuleSetParseException extends RuntimeException {

    public RuleSetParseException(String message) {
        super(message);
    }

    public RuleSetParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
