package com.instagram.model;

import java.util.concurrent.Callable;

/** An app model whose toString runs arbitrary app code (tests re-entrancy). */
public final class Computed {
    private final Callable<String> text;

    public Computed(Callable<String> text) {
        this.text = text;
    }

    @Override
    public String toString() {
        try {
            return text.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
