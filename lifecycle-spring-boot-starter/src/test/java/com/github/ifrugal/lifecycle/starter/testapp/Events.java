package com.github.ifrugal.lifecycle.starter.testapp;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;

import java.util.Map;

/** Event fixtures shared by the slice tests, matching the rule files in {@code src/test/resources/rules}. */
public final class Events {

    private Events() {}

    public static LifecycleEvent pay(String orderId, String shipmentId) {
        return pay(null, orderId, shipmentId);
    }

    public static LifecycleEvent pay(String tenantId, String orderId, String shipmentId) {
        return LifecycleEvent.builder()
                .entity(new EntityRef(tenantId, "order", orderId))
                .action("PAY")
                .actor(Actor.of("u-42", "customer"))
                .payload(Map.of("payment", Map.of("status", "AUTHORISED", "amount", 120),
                        "shipmentId", shipmentId))
                .build();
    }

    public static LifecycleEvent action(String type, String id, String action, String role, Map<String, Object> payload) {
        return LifecycleEvent.builder()
                .entity(EntityRef.of(type, id))
                .action(action)
                .actor(Actor.of("u-42", role))
                .payload(payload)
                .build();
    }
}
