package com.instagram.model;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** An immutable list type that rebuilds itself through copyOf. */
public final class ImmutableList<E> extends AbstractList<E> {
    private final List<E> items;

    private ImmutableList(List<E> items) {
        this.items = items;
    }

    public static <E> ImmutableList<E> copyOf(Collection<? extends E> items) {
        return new ImmutableList<>(new ArrayList<E>(items));
    }

    @Override
    public E get(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }
}
