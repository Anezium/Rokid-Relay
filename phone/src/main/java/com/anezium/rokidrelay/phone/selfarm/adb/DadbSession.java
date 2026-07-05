package com.anezium.rokidrelay.phone.selfarm.adb;

import java.io.IOException;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

public final class DadbSession implements AdbSession {
    private final Dadb dadb;

    private DadbSession(Dadb dadb) {
        this.dadb = dadb;
    }

    public static DadbSession connect(String host, int port, AdbKeyPair keyPair) throws IOException {
        DadbSession session = new DadbSession(Dadb.create(host, port, keyPair, 5000, 15000, false));
        try {
            ShellResult probe = session.shell("echo rokid-relay");
            if (!probe.getOutput().trim().equals("rokid-relay")) {
                throw new IOException("unexpected ADB probe response: " + probe.getAllOutput());
            }
            return session;
        } catch (IOException exception) {
            session.close();
            throw exception;
        }
    }

    @Override
    public ShellResult shell(String command) throws IOException {
        try {
            AdbShellResponse response = dadb.shell(command);
            return new ShellResult(
                    response.getOutput(),
                    response.getErrorOutput(),
                    response.getExitCode());
        } catch (RuntimeException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        try {
            dadb.close();
        } catch (Exception ignored) {
            // Best-effort transport cleanup.
        }
    }
}

