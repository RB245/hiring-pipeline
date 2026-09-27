package com.pipeline.application;

public class NoJobConfiguredException extends RuntimeException {

    public NoJobConfiguredException() {
        super("No job opening exists yet");
    }
}
