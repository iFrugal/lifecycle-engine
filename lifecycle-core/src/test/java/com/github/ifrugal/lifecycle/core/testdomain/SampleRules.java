package com.github.ifrugal.lifecycle.core.testdomain;

import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sample domain used by every test: an order and a shipment whose lifecycles signal each other, a guarded
 * refund with a task and a timer, and a tenant overlay. This is the ONLY place business vocabulary may appear
 * in this repository outside docs (H5). Mirrors DD-04.
 */
public final class SampleRules {

    private SampleRules() {}

    public static final String ORDER = "order";
    public static final String SHIPMENT = "shipment";
    public static final String ENGINE_ROLE = "lifecycle-engine";

    public static RuleSetDocument order() {
        return new RuleSetDocument(null, ORDER, "NEW", 16,
                List.of("NEW", "PAID", "FULFILLING", "COMPLETED", "CANCELLED", "REFUND_PENDING", "REFUNDED"),
                List.of(
                        TransitionDocument.builder("order.pay")
                                .from("NEW").on("PAY").roles("customer", "payments-service")
                                .when(map("payment.status", "AUTHORISED"))
                                .to("PAID")
                                .emit(
                                        EmitDocument.notification("ReceiptRequested", map("orderId", "$entity.id", "amount", "$payload.payment.amount")),
                                        EmitDocument.signal("PREPARE", TargetDocument.of(SHIPMENT, "$payload.shipmentId"), map("orderId", "$entity.id")))
                                .build(),
                        TransitionDocument.builder("order.cancel")
                                .from("*").except("COMPLETED", "REFUNDED", "CANCELLED").on("CANCEL").roles("customer", "support")
                                .to("CANCELLED")
                                .emit(EmitDocument.notification("OrderCancelled", "$payload"))
                                .build(),
                        TransitionDocument.builder("order.on-shipment-prepared")
                                .from("PAID").on("SHIPMENT_PREPARED").roles(ENGINE_ROLE)
                                .to("FULFILLING")
                                .build(),
                        TransitionDocument.builder("order.complete")
                                .from("FULFILLING").on("COMPLETE").roles(ENGINE_ROLE)
                                .to("COMPLETED")
                                .build(),
                        TransitionDocument.builder("order.request-refund")
                                .from("COMPLETED").on("REQUEST_REFUND").roles("customer")
                                .guard(RefundWindowOpen.NAME)
                                .to("REFUND_PENDING")
                                .task(new TaskDocument("approve-refund", Set.of("finance"), "REFUND_DECIDED", null, map("reason", "$payload.reason")))
                                .emit(new EmitDocument("ESCALATE", TargetDocument.toSelf(), null, Duration.ofHours(72), null, null))
                                .build(),
                        TransitionDocument.builder("order.refund-approved")
                                .from("REFUND_PENDING").on("REFUND_DECIDED").roles("finance", "lifecycle-tasks")
                                .when(map("decision", "APPROVED"))
                                .to("REFUNDED")
                                .build(),
                        TransitionDocument.builder("order.refund-rejected")
                                .from("REFUND_PENDING").on("REFUND_DECIDED").roles("finance", "lifecycle-tasks")
                                .when(map("decision", "REJECTED"))
                                .to("COMPLETED")
                                .build(),
                        TransitionDocument.builder("order.escalate")
                                .from("REFUND_PENDING").on("ESCALATE").roles(ENGINE_ROLE)
                                .to("REFUND_PENDING")
                                .emit(EmitDocument.notification("RefundEscalated", map("orderId", "$entity.id")))
                                .build()));
    }

    public static RuleSetDocument shipment() {
        return new RuleSetDocument(null, SHIPMENT, "PENDING", null,
                List.of("PENDING", "PREPARED", "DELIVERED"),
                List.of(
                        TransitionDocument.builder("shipment.prepare")
                                .from("PENDING").on("PREPARE").roles(ENGINE_ROLE)
                                .to("PREPARED")
                                .emit(EmitDocument.signal("SHIPMENT_PREPARED", TargetDocument.of(ORDER, "$payload.orderId"), map("shipmentId", "$entity.id")))
                                .build(),
                        TransitionDocument.builder("shipment.deliver")
                                .from("PREPARED").on("DELIVER").roles("courier")
                                .to("DELIVERED")
                                .emit(EmitDocument.signal("COMPLETE", TargetDocument.of(ORDER, "$payload.orderId"), map("shipmentId", "$entity.id")))
                                .build()));
    }

    /** Tenant "acme": replaces the refund edge, disables cancellation, adds a hold state and edge (DD-05). */
    public static RuleSetDocument acmeOverlay() {
        return new RuleSetDocument("acme", ORDER, null, null,
                List.of("ON_HOLD"),
                List.of(
                        TransitionDocument.builder("order.request-refund")
                                .from("COMPLETED").on("REQUEST_REFUND").roles("customer", "store-manager")
                                .when(map("channel", "STORE"))
                                .to("REFUND_PENDING")
                                .task(new TaskDocument("approve-refund", Set.of("store-manager"), "REFUND_DECIDED", null, null))
                                .build(),
                        TransitionDocument.disabled("order.cancel"),
                        TransitionDocument.builder("order.hold")
                                .from("PAID").on("HOLD").roles("store-manager")
                                .to("ON_HOLD")
                                .build()));
    }

    public static List<RuleSetDocument> all() {
        return List.of(order(), shipment(), acmeOverlay());
    }

    /** Ordered map literal for `when` maps and payload templates. */
    public static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
