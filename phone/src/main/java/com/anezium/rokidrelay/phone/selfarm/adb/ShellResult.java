package com.anezium.rokidrelay.phone.selfarm.adb;

public final class ShellResult {
    private final String output;
    private final String errorOutput;
    private final int exitCode;

    ShellResult(String output, String errorOutput, int exitCode) {
        this.output = output == null ? "" : output;
        this.errorOutput = errorOutput == null ? "" : errorOutput;
        this.exitCode = exitCode;
    }

    public String getOutput() {
        return output;
    }

    public String getErrorOutput() {
        return errorOutput;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getAllOutput() {
        return output + errorOutput;
    }
}

