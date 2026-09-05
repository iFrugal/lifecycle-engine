package com.github.ifrugal.lifecycle.rules.yaml;

import com.github.ifrugal.lifecycle.api.rules.Dispatch;
import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parses {@code rules/order.yaml} and checks it field-by-field against the equivalent literals in
 * {@code SampleRules.order()} (lifecycle-core test sources, not on this module's classpath), plus the
 * format/error-handling behaviour of {@link YamlRuleSetParser} itself.
 */
class YamlRuleSetParserTest {

    private final YamlRuleSetParser parser = new YamlRuleSetParser();

    private static String resource(String name) {
        try {
            return Files.readString(Path.of(YamlRuleSetParserTest.class.getResource("/" + name).toURI()));
        } catch (IOException | java.net.URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    private static TransitionDocument findTransition(RuleSetDocument doc, String id) {
        return doc.transitions().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no transition " + id));
    }

    @Test
    void parsesOrderYamlStructurally() {
        RuleSetDocument doc = parser.parse(resource("rules/order.yaml"), "yaml");

        assertThat(doc.entityType()).isEqualTo("order");
        assertThat(doc.tenantId()).isNull();
        assertThat(doc.isOverlay()).isFalse();
        assertThat(doc.initial()).isEqualTo("NEW");
        assertThat(doc.maxHops()).isEqualTo(16);
        assertThat(doc.states()).containsExactly("NEW", "PAID", "FULFILLING", "COMPLETED", "CANCELLED", "REFUND_PENDING", "REFUNDED");
        assertThat(doc.transitions()).hasSize(8);

        TransitionDocument pay = findTransition(doc, "order.pay");
        assertThat(pay.from()).isEqualTo("NEW");
        assertThat(pay.on()).isEqualTo("PAY");
        assertThat(pay.to()).isEqualTo("PAID");
        assertThat(pay.roles()).containsExactlyInAnyOrder("customer", "payments-service");
        assertThat(pay.when()).containsEntry("payment.status", "AUTHORISED");
        assertThat(pay.emit()).hasSize(2);

        EmitDocument receipt = pay.emit().get(0);
        assertThat(receipt.action()).isEqualTo("ReceiptRequested");
        assertThat(receipt.isSignal()).isFalse();
        assertThat(receipt.to()).isNull();
        assertThat(receipt.payload()).isEqualTo(Map.of("orderId", "$entity.id", "amount", "$payload.payment.amount"));

        EmitDocument prepare = pay.emit().get(1);
        assertThat(prepare.action()).isEqualTo("PREPARE");
        assertThat(prepare.isSignal()).isTrue();
        assertThat(prepare.to()).isEqualTo(TargetDocument.of("shipment", "$payload.shipmentId"));

        TransitionDocument cancel = findTransition(doc, "order.cancel");
        assertThat(cancel.from()).isEqualTo("*");
        assertThat(cancel.except()).containsExactly("COMPLETED", "REFUNDED", "CANCELLED");
        assertThat(cancel.emit()).hasSize(1);
        assertThat(cancel.emit().get(0).payload()).isEqualTo("$payload");

        TransitionDocument requestRefund = findTransition(doc, "order.request-refund");
        assertThat(requestRefund.guard()).isEqualTo("refund-window-open");
        TaskDocument task = requestRefund.task();
        assertThat(task).isNotNull();
        assertThat(task.name()).isEqualTo("approve-refund");
        assertThat(task.assignTo()).containsExactly("finance");
        assertThat(task.onCompleteAction()).isEqualTo("REFUND_DECIDED");
        assertThat(task.onCompleteTarget()).isNull();
        assertThat(task.payload()).isEqualTo(Map.of("reason", "$payload.reason"));

        assertThat(requestRefund.emit()).hasSize(1);
        EmitDocument escalate = requestRefund.emit().get(0);
        assertThat(escalate.action()).isEqualTo("ESCALATE");
        assertThat(escalate.to()).isEqualTo(TargetDocument.toSelf());
        assertThat(escalate.after()).isEqualTo(Duration.ofHours(72));
        assertThat(escalate.dispatch()).isNull();
    }

    @Test
    void parsesShipmentYaml() {
        RuleSetDocument doc = parser.parse(resource("rules/shipment.yaml"), "yaml");
        assertThat(doc.entityType()).isEqualTo("shipment");
        assertThat(doc.maxHops()).isNull();
        assertThat(doc.transitions()).hasSize(2);
    }

    @Test
    void parsesAcmeOverlayYaml() {
        RuleSetDocument doc = parser.parse(resource("rules/tenants/acme/order.yaml"), "yaml");
        assertThat(doc.tenantId()).isEqualTo("acme");
        assertThat(doc.isOverlay()).isTrue();
        assertThat(doc.entityType()).isEqualTo("order");
        assertThat(doc.states()).containsExactly("ON_HOLD");

        TransitionDocument disabledCancel = findTransition(doc, "order.cancel");
        assertThat(disabledCancel.disabled()).isTrue();
        assertThat(disabledCancel.from()).isNull();

        TransitionDocument refund = findTransition(doc, "order.request-refund");
        assertThat(refund.roles()).containsExactlyInAnyOrder("customer", "store-manager");
        assertThat(refund.when()).containsEntry("channel", "STORE");
        assertThat(refund.task().assignTo()).containsExactly("store-manager");
    }

    @Test
    void acceptsFormatCaseInsensitively() {
        String body = "entityType: widget\ninitial: NEW\n";
        assertThat(parser.parse(body, "YAML").entityType()).isEqualTo("widget");
        assertThat(parser.parse(body, "Yml").entityType()).isEqualTo("widget");
        assertThat(parser.parse("{\"entityType\": \"widget\"}", "JSON").entityType()).isEqualTo("widget");
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> parser.parse("entityType: widget", "xml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonVariantParses() {
        String json = """
                {
                  "entityType": "widget",
                  "initial": "NEW",
                  "states": ["NEW", "DONE"],
                  "transitions": [
                    { "id": "widget.finish", "from": "NEW", "on": "FINISH", "to": "DONE" }
                  ]
                }
                """;
        RuleSetDocument doc = parser.parse(json, "json");
        assertThat(doc.entityType()).isEqualTo("widget");
        assertThat(doc.transitions()).hasSize(1);
        assertThat(doc.transitions().get(0).to()).isEqualTo("DONE");
    }

    @Test
    void missingEntityTypeFails() {
        assertThatThrownBy(() -> parser.parse("initial: NEW\n", "yaml"))
                .isInstanceOf(RuleSetParseException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    void unknownTopLevelKeyNamesTheKey() {
        assertThatThrownBy(() -> parser.parse("entityType: widget\nbogus: 1\n", "yaml"))
                .isInstanceOf(RuleSetParseException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void unknownTransitionKeyNamesTheKeyPath() {
        String body = """
                entityType: widget
                transitions:
                  - id: widget.finish
                    from: NEW
                    on: FINISH
                    to: DONE
                    notAField: true
                """;
        assertThatThrownBy(() -> parser.parse(body, "yaml"))
                .isInstanceOf(RuleSetParseException.class)
                .hasMessageContaining("transitions")
                .hasMessageContaining("notAField");
    }

    @Test
    void dispatchTransportWithReasonParses() {
        String body = """
                entityType: widget
                transitions:
                  - id: widget.escalate
                    from: NEW
                    on: ESCALATE
                    to: NEW
                    emit:
                      - action: ESCALATE
                        to: self
                        after: 72h
                        dispatch: transport
                        reason: "why this deviates from the default"
                """;
        RuleSetDocument doc = parser.parse(body, "yaml");
        EmitDocument emit = doc.transitions().get(0).emit().get(0);
        assertThat(emit.dispatch()).isEqualTo(Dispatch.TRANSPORT);
        assertThat(emit.reason()).isEqualTo("why this deviates from the default");
    }

    @Test
    void invalidDispatchValueFails() {
        String body = """
                entityType: widget
                transitions:
                  - id: widget.escalate
                    from: NEW
                    on: ESCALATE
                    to: NEW
                    emit:
                      - action: ESCALATE
                        to: self
                        dispatch: carrier-pigeon
                """;
        assertThatThrownBy(() -> parser.parse(body, "yaml"))
                .isInstanceOf(RuleSetParseException.class);
    }

    @Test
    void emitToSelfAndEmitToTypeIdBothResolve() {
        String body = """
                entityType: widget
                transitions:
                  - id: widget.a
                    from: NEW
                    on: A
                    to: NEW
                    emit:
                      - action: SELF_SIGNAL
                        to: self
                      - action: OTHER_SIGNAL
                        to: { type: other, id: 42 }
                """;
        RuleSetDocument doc = parser.parse(body, "yaml");
        List<EmitDocument> emits = doc.transitions().get(0).emit();
        assertThat(emits.get(0).to()).isEqualTo(TargetDocument.toSelf());
        assertThat(emits.get(1).to()).isEqualTo(TargetDocument.of("other", 42));
    }

    @Test
    void whenValuesKeepYamlTypes() {
        String body = """
                entityType: widget
                transitions:
                  - id: widget.a
                    from: NEW
                    on: A
                    to: NEW
                    when: { count: 3, flag: true, tags: [a, b], gate: { exists: true } }
                """;
        Map<String, Object> when = parser.parse(body, "yaml").transitions().get(0).when();
        assertThat(when.get("count")).isEqualTo(3);
        assertThat(when.get("flag")).isEqualTo(true);
        assertThat(when.get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(when.get("gate")).isEqualTo(Map.of("exists", true));
    }

    @Test
    void invalidAfterDurationFails() {
        String body = """
                entityType: widget
                transitions:
                  - id: widget.a
                    from: NEW
                    on: A
                    to: NEW
                    emit:
                      - action: X
                        to: self
                        after: not-a-duration
                """;
        assertThatThrownBy(() -> parser.parse(body, "yaml"))
                .isInstanceOf(RuleSetParseException.class);
    }
}
