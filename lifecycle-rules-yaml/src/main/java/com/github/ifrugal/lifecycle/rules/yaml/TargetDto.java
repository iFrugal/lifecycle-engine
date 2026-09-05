package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Wire shape of {@code {type, id}} — where a signal is targeted, when it is not {@code self}. */
final class TargetDto {

    @JsonProperty("type")
    String type;

    @JsonProperty("id")
    Object id;
}
