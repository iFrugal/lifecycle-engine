package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shape of one emitted output (DD-04). {@code to} is polymorphic: either the literal string {@code self} or
 * a map with {@code type}/{@code id}, so it is read as a raw {@link Object} and resolved by
 * {@link YamlRuleSetParser}.
 */
final class EmitDto {

    @JsonProperty("action")
    String action;

    @JsonProperty("to")
    Object to;

    @JsonProperty("payload")
    Object payload;

    @JsonProperty("after")
    String after;

    @JsonProperty("dispatch")
    String dispatch;

    @JsonProperty("reason")
    String reason;
}
