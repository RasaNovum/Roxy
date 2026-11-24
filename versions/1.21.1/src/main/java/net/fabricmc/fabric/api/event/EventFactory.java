package net.fabricmc.fabric.api.event;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

public final class EventFactory {
    private EventFactory() {
    }

    @SuppressWarnings("unchecked")
    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        return new ArrayBackedEvent<>((Class<T>) type, invokerFactory);
    }

    private static final class ArrayBackedEvent<T> extends Event<T> {
        private final Function<T[], T> invokerFactory;
        private T[] handlers;

        @SuppressWarnings("unchecked")
        private ArrayBackedEvent(Class<T> type, Function<T[], T> invokerFactory) {
            this.invokerFactory = invokerFactory;
            this.handlers = (T[]) Array.newInstance(type, 0);
            this.invoker = invokerFactory.apply(this.handlers);
        }

        @Override
        public synchronized void register(T listener) {
            Objects.requireNonNull(listener, "listener");
            T[] next = Arrays.copyOf(handlers, handlers.length + 1);
            next[handlers.length] = listener;
            handlers = next;
            invoker = invokerFactory.apply(next);
        }
    }
}
