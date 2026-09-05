package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Wire shape of one rule-set document (DD-04). Converted to {@code RuleSetDocument} after parsing. */
final class RuleSetDto {

    @JsonProperty("entityType")
    String entityType;

    @JsonProperty("tenant")
    String tenant;

    @JsonProperty("initial")
    String initial;

    @JsonProperty("maxHops")
    Integer maxHops;

    @JsonProperty("states")
    List<String> states;

    @JsonProperty("transitions")
    List<TransitionDto> transitions;
}
