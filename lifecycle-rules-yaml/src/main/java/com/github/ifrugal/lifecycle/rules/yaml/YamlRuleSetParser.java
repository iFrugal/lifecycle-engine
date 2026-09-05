package com.github.ifrugal.lifecycle.rules.yaml;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Parses a rule-set body written in the DD-04 schema into the {@code RuleSetDocument} model. Accepts
 * {@code yaml}, {@code yml}, and {@code json} (case-insensitive); anything else is a programmer error, not a
 * data problem, so it throws {@link IllegalArgumentException}. Every other failure — malformed syntax, an
 * unknown field, a missing {@code entityType} — throws {@link RuleSetParseException}.
 */
public final class YamlRuleSetParser implements RuleSetParser {

    private static final ObjectMapper YAML_MAPPER = strict(new ObjectMapper(new YAMLFactory()));
    private static final ObjectMapper JSON_MAPPER = strict(new ObjectMapper());

    private static ObjectMapper strict(ObjectMapper mapper) {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return mapper;
    }

    @Override
    public RuleSetDocument parse(String body, String format) {
        ObjectMapper mapper = mapperFor(format);

        RuleSetDto dto;
        try {
            dto = mapper.readValue(body, RuleSetDto.class);
        } catch (UnrecognizedPropertyException e) {
            throw unknownField(e);
        } catch (JsonProcessingException e) {
            throw new RuleSetParseException("malformed rule set body: " + e.getOriginalMessage() + location(e), e);
        } catch (IOException e) {
            throw new RuleSetParseException("failed to read rule set body", e);
        }
        if (dto == null) {
            throw new RuleSetParseException("empty rule set document");
        }
        return toDocument(dto, mapper);
    }

    private static ObjectMapper mapperFor(String format) {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        return switch (format.trim().toLowerCase(Locale.ROOT)) {
            case "yaml", "yml" -> YAML_MAPPER;
            case "json" -> JSON_MAPPER;
            default -> throw new IllegalArgumentException("unsupported rule set format: '" + format + "' (expected yaml, yml, or json)");
        };
    }

    private RuleSetDocument toDocument(RuleSetDto dto, ObjectMapper mapper) {
        if (dto.entityType == null || dto.entityType.isBlank()) {
            throw new RuleSetParseException("missing required field 'entityType'");
        }
        List<TransitionDocument> transitions = dto.transitions == null
                ? List.of()
                : dto.transitions.stream().map(t -> toTransition(t, mapper)).toList();
        return new RuleSetDocument(dto.tenant, dto.entityType, dto.initial, dto.maxHops, dto.states, transitions);
    }

    private TransitionDocument toTransition(TransitionDto t, ObjectMapper mapper) {
        List<EmitDocument> emit = t.emit == null ? List.of() : t.emit.stream().map(e -> toEmit(e, mapper)).toList();
        TaskDocument task = t.task == null ? null : toTask(t.task, mapper);
        return new TransitionDocument(t.id, t.from, t.except, t.on, t.roles, t.when, t.guard, t.to, emit, task, t.disabled);
    }

    private EmitDocument toEmit(EmitDto e, ObjectMapper mapper) {
        TargetDocument target = toTarget(e.to, mapper);
        Duration after = parseAfter(e.after);
        Dispatch dispatch = parseDispatch(e.dispatch);
        return new EmitDocument(e.action, target, e.payload, after, dispatch, e.reason);
    }

    private Duration parseAfter(String after) {
        if (after == null) {
            return null;
        }
        try {
            return Durations.parse(after);
        } catch (IllegalArgumentException ex) {
            throw new RuleSetParseException("invalid 'after' duration: " + ex.getMessage(), ex);
        }
    }

    private Dispatch parseDispatch(String dispatch) {
        if (dispatch == null) {
            return null;
        }
        try {
            return Dispatch.valueOf(dispatch.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new RuleSetParseException("invalid 'dispatch' value: '" + dispatch + "' (expected inline or transport)", ex);
        }
    }

    private TargetDocument toTarget(Object to, ObjectMapper mapper) {
        if (to == null) {
            return null;
        }
        if (to instanceof String s) {
            if (s.equalsIgnoreCase("self")) {
                return TargetDocument.toSelf();
            }
            throw new RuleSetParseException("invalid emit 'to' value: '" + s + "' (expected 'self' or a map with type/id)");
        }
        TargetDto dto = convert(to, TargetDto.class, mapper, "emit.to");
        if (dto.type == null || dto.type.isBlank()) {
            throw new RuleSetParseException("emit 'to' is missing required field 'type'");
        }
        return TargetDocument.of(dto.type, dto.id);
    }

    private TaskDocument toTask(TaskDto t, ObjectMapper mapper) {
        String onCompleteAction = null;
        TargetDocument onCompleteTarget = null;
        if (t.onComplete != null) {
            onCompleteAction = t.onComplete.action;
            onCompleteTarget = fromTargetDto(t.onComplete.target);
        }
        return new TaskDocument(t.name, t.assignTo, onCompleteAction, onCompleteTarget, t.payload);
    }

    private TargetDocument fromTargetDto(TargetDto dto) {
        if (dto == null) {
            return null;
        }
        if (dto.type == null || dto.type.isBlank()) {
            throw new RuleSetParseException("task.onComplete.target is missing required field 'type'");
        }
        return TargetDocument.of(dto.type, dto.id);
    }

    private <T> T convert(Object raw, Class<T> type, ObjectMapper mapper, String path) {
        try {
            return mapper.convertValue(raw, type);
        } catch (IllegalArgumentException ex) {
            if (ex.getCause() instanceof UnrecognizedPropertyException upe) {
                throw unknownField(upe);
            }
            throw new RuleSetParseException("invalid value at '" + path + "': " + ex.getMessage(), ex);
        }
    }

    private RuleSetParseException unknownField(UnrecognizedPropertyException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        if (!path.isEmpty()) {
            path.append('.');
        }
        path.append(e.getPropertyName());
        return new RuleSetParseException("unknown field '" + path + "'" + location(e));
    }

    private static String location(JsonProcessingException e) {
        JsonLocation l = e.getLocation();
        if (l == null || (l.getLineNr() < 0 && l.getColumnNr() < 0)) {
            return "";
        }
        return " at line " + l.getLineNr() + ", column " + l.getColumnNr();
    }
}
