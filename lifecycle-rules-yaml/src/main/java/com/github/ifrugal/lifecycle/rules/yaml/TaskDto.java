package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/** Wire shape of a transition's {@code task} block (DD-10). */
final class TaskDto {

    @JsonProperty("name")
    String name;

    @JsonProperty("assignTo")
    Set<String> assignTo;

    @JsonProperty("onComplete")
    OnCompleteDto onComplete;

    @JsonProperty("payload")
    Object payload;
}
