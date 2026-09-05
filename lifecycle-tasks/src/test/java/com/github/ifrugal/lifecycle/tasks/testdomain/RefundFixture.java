package com.github.ifrugal.lifecycle.tasks.testdomain;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The refund fragment of {@code lifecycle-core}'s {@code testdomain.SampleRules}, copied here because that class
 * is a test fixture of another module and not on this module's classpath. {@code order} starts at
 * {@code COMPLETED} (skipping the pay/ship/fulfil edges that don't matter to a tasks test) and adds one edge
 * whose task targets a different entity ({@code shipment}), exercising the deferred cross-entity signal.
 */
public final class RefundFixture {

    public static final String ORDER = "order";
    public static final String SHIPMENT = "shipment";

    private RefundFixture() {}

    public static RuleSetDocument order() {
        return new RuleSetDocument(null, ORDER, "COMPLETED", null,
                List.of("COMPLETED", "REFUND_PENDING", "REFUNDED"),
                List.of(
                        TransitionDocument.builder("order.request-refund")
                                .from("COMPLETED").on("REQUEST_REFUND").roles("customer")
                                .to("REFUND_PENDING")
                                .task(new TaskDocument("approve-refund", Set.of("finance"), "REFUND_DECIDED", null,
                                        map("reason", "$payload.reason")))
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
                        TransitionDocument.builder("order.request-hold")
                                .from("COMPLETED").on("REQUEST_HOLD").roles("customer")
                                .to("COMPLETED")
                                .task(new TaskDocument("release-shipment", Set.of("ops"), "RELEASE_DECIDED",
                                        TargetDocument.of(SHIPMENT, "$payload.shipmentId"), null))
                                .build()));
    }

    public static RuleSetDocument shipment() {
        return new RuleSetDocument(null, SHIPMENT, "PENDING", null,
                List.of("PENDING", "RELEASED"),
                List.of(
                        TransitionDocument.builder("shipment.release")
                                .from("PENDING").on("RELEASE_DECIDED").roles("ops", "lifecycle-tasks")
                                .to("RELEASED")
                                .build()));
    }

    public static List<RuleSetDocument> all() {
        return List.of(order(), shipment());
    }

    private static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
