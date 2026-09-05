package com.github.ifrugal.lifecycle.api;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.TransitionView;

import java.util.List;

/** The engine surface (DD-11). Three methods; nothing else is public behaviour. */
public interface LifecycleEngine {

    /** Read, decide, commit, publish. Inline cascades complete before this returns (DD-09). */
    Outcome handle(LifecycleEvent event);

    /** Read and decide only. Safe to call speculatively and often (R9, H3). */
    Decision evaluate(LifecycleEvent event);

    /** Transitions this actor could attempt from the entity's current state, ignoring payload conditions. */
    List<TransitionView> available(EntityRef ref, Actor actor);
}
