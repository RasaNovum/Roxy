package net.rasanovum.roxy.compat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public final class CopycatMaterialPolicy {
    private CopycatMaterialPolicy() {
    }

    public static <T> T representative(Iterable<T> candidates, Predicate<T> usable, Function<T, String> stableKey) {
        Map<T, Integer> counts = new HashMap<>();
        for (T candidate : candidates) {
            if (candidate != null && usable.test(candidate)) counts.merge(candidate, 1, Integer::sum);
        }
        return counts.entrySet().stream().sorted(Comparator
                        .<Map.Entry<T, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> stableKey.apply(entry.getKey())))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
