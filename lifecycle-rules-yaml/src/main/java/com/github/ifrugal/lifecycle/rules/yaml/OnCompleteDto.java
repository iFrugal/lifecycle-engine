package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Wire shape of a task's {@code onComplete} block: the signal raised when the task finishes. */
final class OnCompleteDto {

    @JsonProperty("action")
    String action;

    @JsonProperty("target")
    TargetDto target;
}
