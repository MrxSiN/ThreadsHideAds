package com.instagram.model;

/** An app model whose toString is structured, like a Kotlin data class. */
public class Structured {
    public final String text;
    public Object child;

    public Structured(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }
}
