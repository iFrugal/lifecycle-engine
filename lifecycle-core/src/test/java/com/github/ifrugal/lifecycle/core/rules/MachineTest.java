package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MachineTest {

    private Machine orderMachine() {
        RuleCompiler.Result r = new RuleCompiler().compile(SampleRules.order(), GuardRegistry.of(new RefundWindowOpen()), Set.of("order", "shipment"));
        assertThat(r.ok()).as("compile problems: %s", r.problems()).isTrue();
        return r.machine();
    }

    @Test
    void forActionReturnsEveryTransitionDeclaringThatAction() {
        Machine m = orderMachine();
        assertThat(m.forAction("PAY")).extracting("id").containsExactly("order.pay");
        assertThat(m.forAction("REQUEST_REFUND")).extracting("id").containsExactly("order.request-refund");
        // two edges both declare REFUND_DECIDED (approved / rejected)
        assertThat(m.forAction("REFUND_DECIDED")).extracting("id")
                .containsExactlyInAnyOrder("order.refund-approved", "order.refund-rejected");
    }

    @Test
    void forActionOfUnknownActionIsEmpty() {
        Machine m = orderMachine();
        assertThat(m.forAction("NO_SUCH_ACTION")).isEmpty();
    }

    @Test
    void actionsListsEveryDistinctActionSorted() {
        Machine m = orderMachine();
        assertThat(m.actions()).containsExactly(
                "CANCEL", "COMPLETE", "ESCALATE", "PAY", "REFUND_DECIDED", "REQUEST_REFUND", "SHIPMENT_PREPARED");
    }
}
