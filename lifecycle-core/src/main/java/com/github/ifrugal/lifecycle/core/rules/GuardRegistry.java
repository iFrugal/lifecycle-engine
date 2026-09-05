package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Named guards available to rules. The compiler refuses a rule set naming a guard that is not here. */
public final class GuardRegistry {

    private final Map<String, GuardPredicate> byName;

    private GuardRegistry(Map<String, GuardPredicate> byName) {
        this.byName = Map.copyOf(byName);
    }

    public static GuardRegistry empty() {
        return new GuardRegistry(Map.of());
    }

    public static GuardRegistry of(GuardPredicate... guards) {
        return of(List.of(guards));
    }

    public static GuardRegistry of(Collection<? extends GuardPredicate> guards) {
        LinkedHashMap<String, GuardPredicate> m = new LinkedHashMap<>();
        for (GuardPredicate g : guards) {
            if (m.put(g.name(), g) != null) {
                throw new IllegalArgumentException("duplicate guard name " + g.name());
            }
        }
        return new GuardRegistry(m);
    }

    public Optional<GuardPredicate> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<String> names() {
        return byName.keySet();
    }
}
