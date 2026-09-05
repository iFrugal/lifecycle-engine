package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wire shape of one transition (DD-04). */
final class TransitionDto {

    @JsonProperty("id")
    String id;

    @JsonProperty("from")
    String from;

    @JsonProperty("except")
    List<String> except;

    @JsonProperty("on")
    String on;

    @JsonProperty("roles")
    Set<String> roles;

    @JsonProperty("when")
    Map<String, Object> when;

    @JsonProperty("guard")
    String guard;

    @JsonProperty("to")
    String to;

    @JsonProperty("emit")
    List<EmitDto> emit;

    @JsonProperty("task")
    TaskDto task;

    @JsonProperty("disabled")
    boolean disabled;
}
