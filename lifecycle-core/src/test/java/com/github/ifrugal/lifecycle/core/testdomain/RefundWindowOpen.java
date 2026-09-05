package com.github.ifrugal.lifecycle.core.testdomain;

import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;

/** The D1 escape hatch in the sample domain: refunds are allowed unless the payload says the window is closed. */
public final class RefundWindowOpen implements GuardPredicate {

    public static final String NAME = "refund-window-open";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean test(GuardContext ctx) {
        return !Boolean.TRUE.equals(ctx.event().payload().get("refundWindowClosed"));
    }
}
