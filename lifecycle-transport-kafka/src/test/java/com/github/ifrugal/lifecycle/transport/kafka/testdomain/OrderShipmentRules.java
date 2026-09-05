package com.github.ifrugal.lifecycle.transport.kafka.testdomain;

import com.github.ifrugal.lifecycle.api.rules.EmitDocument;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TargetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order/shipment cross-entity cascade from {@code lifecycle-core}'s
 * {@code com.github.ifrugal.lifecycle.core.testdomain.SampleRules} (NOT on this module's classpath — that test
 * fixture is copied here, trimmed to what this module's acceptance test needs), with the refund sub-flow (and
 * its {@code after} timer edge) removed entirely so {@code DefinitionRegistry.requiresDelay()} is {@code false}
 * and this rule set can run over {@link com.github.ifrugal.lifecycle.transport.kafka.KafkaTransport}, which does
 * not support delayed delivery (DD-09).
 */
public final class OrderShipmentRules {

    private OrderShipmentRules() {}

    public static final String ORDER = "order";
    public static final String SHIPMENT = "shipment";
    public static final String ENGINE_ROLE = "lifecycle-engine";

    public static RuleSetDocument order() {
        return new RuleSetDocument(null, ORDER, "NEW", 16,
                List.of("NEW", "PAID", "FULFILLING", "COMPLETED", "CANCELLED"),
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
                                .from("*").except("COMPLETED", "CANCELLED").on("CANCEL").roles("customer", "support")
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

    public static List<RuleSetDocument> all() {
        return List.of(order(), shipment());
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
