package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;

/** Parses a rule set body (YAML or JSON) into the document model. Implemented in lifecycle-rules-yaml. */
public interface RuleSetParser {

    /** @param format {@code yaml} or {@code json} */
    RuleSetDocument parse(String body, String format);
}
