package com.anezium.rokidrelay.phone.selfarm.adb;

import java.io.IOException;

public interface AdbSession extends AutoCloseable {
    ShellResult shell(String command) throws IOException;

    @Override
    void close();

    default String runChecked(String command) throws IOException {
        ShellResult response = shell(command);
        if (response.getExitCode() != 0) {
            throw new IOException("ADB shell command failed with exit " + response.getExitCode() + "\n"
                    + response.getErrorOutput()
                    + response.getOutput());
        }
        return response.getOutput();
    }
}
