package net.fabricmc.fabric.api.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Event<T> {
    private final CopyOnWriteArrayList<T> listeners = new CopyOnWriteArrayList<>();

    public void register(T listener) {
        listeners.add(listener);
    }

    public List<T> listeners() {
        return List.copyOf(listeners);
    }
}
