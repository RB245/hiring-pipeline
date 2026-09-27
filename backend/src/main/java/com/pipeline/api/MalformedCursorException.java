package com.pipeline.api;

class MalformedCursorException extends RuntimeException {

    MalformedCursorException(String cursor) {
        super("Not a usable cursor: " + cursor);
    }
}
