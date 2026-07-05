package com.anezium.rokidrelay.phone.selfarm.adb;

import android.content.Context;
import android.util.Log;

import com.anezium.rokidrelay.phone.Constants;
import com.flyfishxu.kadb.Kadb;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dadb.AdbKeyPair;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

public final class AdbBridgeClient {
    private static final String TAG = "RelayAdbBridge";
    private static final long PAIRING_TIMEOUT_MS = 12000L;
    private static final int DEFAULT_ADB_PORT = 5555;
    private static volatile boolean kadbCertConfigured;

    private final Context context;
    private final AdbMdnsPairingResolver mdnsResolver;

    public AdbBridgeClient(Context context) {
        this.context = context.getApplicationContext();
        this.mdnsResolver = new AdbMdnsPairingResolver(this.context);
    }

    public BootstrapResult bootstrapWirelessDebugging(
            String host,
            int pairingPort,
            String pairingCode,
            int connectPort,
            String adbPublicKey) throws IOException {
        String cleanHost = clean(host);
        String cleanCode = clean(pairingCode);
        if (cleanCode.isEmpty()) {
            throw new IOException("Wireless Debugging pairing code is missing");
        }
        if (adbPublicKey == null || adbPublicKey.trim().isEmpty()) {
            throw new IOException("Relay recovery ADB public key is missing");
        }

        AdbMdnsPairingResolver.PairingEndpoint pairEndpoint =
                pairingPort > 0 && !cleanHost.isEmpty()
                        ? new AdbMdnsPairingResolver.PairingEndpoint(cleanHost, pairingPort)
                        : mdnsResolver.resolvePairingEndpoint(cleanHost);
        pairWirelessDebugging(pairEndpoint.host, pairEndpoint.port, cleanCode);

        String connectHost = cleanHost.isEmpty() ? pairEndpoint.host : cleanHost;
        AdbMdnsPairingResolver.PairingEndpoint connectEndpoint =
                connectPort > 0 && !connectHost.isEmpty()
                        ? new AdbMdnsPairingResolver.PairingEndpoint(connectHost, connectPort)
                        : mdnsResolver.resolveConnectEndpoint(connectHost);

        try (AdbSession adb = connect(connectEndpoint.host, connectEndpoint.port)) {
            String output = adb.runChecked(buildBootstrapCommand(adbPublicKey));
            return new BootstrapResult(
                    pairEndpoint.host,
                    pairEndpoint.port,
                    connectEndpoint.host,
                    connectEndpoint.port,
                    output);
        }
    }

    public AdbSession connect(String host, int port) throws IOException {
        ensurePhoneWifiReachable(host);
        if (port != DEFAULT_ADB_PORT) {
            Throwable kadbError = null;
            try {
                configureKadbCert();
                return KadbSession.connect(host, port);
            } catch (Throwable throwable) {
                kadbError = throwable;
            }
            try {
                return DadbSession.connect(host, port, readOrCreateDadbKeyPair());
            } catch (Throwable dadbError) {
                IOException failure = new IOException(
                        "Could not open Wireless Debugging port " + port
                                + ". Put the phone and glasses on the same Wi-Fi, then retry the Wireless Debugging bootstrap. "
                                + "KADB: " + shortMessage(kadbError)
                                + "; dadb: " + shortMessage(dadbError),
                        kadbError);
                failure.addSuppressed(dadbError);
                throw failure;
            }
        }
        return DadbSession.connect(host, port, readOrCreateDadbKeyPair());
    }

    public void pairWirelessDebugging(String host, int port, String code) throws IOException {
        if (host == null || host.trim().isEmpty() || code == null || code.trim().isEmpty() || port <= 0) {
            throw new IOException("Wireless Debugging pairing details are incomplete");
        }
        ensurePhoneWifiReachable(host);
        configureKadbCert();
        String cleanHost = host.trim();
        String cleanCode = code.trim();
        Log.d(TAG, "pair start host=" + redactHost(cleanHost)
                + " port=" + port
                + " codeLen=" + cleanCode.length());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread pairingThread = new Thread(() -> {
            try {
                runKadbPair(cleanHost, port, cleanCode);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }, "relay-adb-pair");
        pairingThread.setDaemon(true);
        pairingThread.start();
        long deadline = android.os.SystemClock.elapsedRealtime() + PAIRING_TIMEOUT_MS;
        try {
            while (done.getCount() > 0) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    pairingThread.interrupt();
                    throw new IOException("Wireless Debugging pairing timed out");
                }
                done.await(Math.min(remaining, 250L), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            pairingThread.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("Wireless Debugging pairing was interrupted", exception);
        }
        Throwable cause = failure.get();
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        if (cause != null) {
            throw new IOException("Wireless Debugging pairing failed: " + shortMessage(cause), cause);
        }
        Log.d(TAG, "pair success host=" + redactHost(cleanHost) + " port=" + port);
    }

    private void runKadbPair(String host, int port, String code) throws InterruptedException {
        BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (CoroutineScope scope, Continuation<? super Unit> continuation) ->
                        Kadb.Companion.pair(
                                host,
                                port,
                                code,
                                "Rokid Relay",
                                continuation));
    }

    public static String buildBootstrapCommand(String adbPublicKey) {
        String quotedPublicKey = shellQuote(adbPublicKey.trim());
        return "set -e\n"
                + "PUB=" + quotedPublicKey + "\n"
                + "pm grant " + Constants.CLIENT_PACKAGE
                + " android.permission.WRITE_SECURE_SETTINGS\n"
                + "settings put global adb_wifi_enabled 1\n"
                + "setprop persist.adb.tcp.port " + DEFAULT_ADB_PORT + "\n"
                + "setprop service.adb.tcp.port " + DEFAULT_ADB_PORT + " >/dev/null 2>&1 || true\n"
                + "mkdir -p /data/misc/adb\n"
                + "touch /data/misc/adb/adb_keys\n"
                + "grep -qxF \"$PUB\" /data/misc/adb/adb_keys 2>/dev/null "
                + "|| printf '%s\\n' \"$PUB\" >> /data/misc/adb/adb_keys\n"
                + "chmod 640 /data/misc/adb/adb_keys 2>/dev/null || true\n"
                + "GRANTED=$(dumpsys package " + Constants.CLIENT_PACKAGE
                + " 2>/dev/null | grep -A3 android.permission.WRITE_SECURE_SETTINGS "
                + "| grep -c 'granted=true' || true)\n"
                + "echo ROKID_RELAY_WIRELESS_BOOTSTRAP grant=$GRANTED port=$(getprop persist.adb.tcp.port)\n"
                + "(setprop ctl.restart adbd >/dev/null 2>&1 || true) &\n";
    }

    private synchronized void configureKadbCert() {
        if (kadbCertConfigured) {
            return;
        }
        File privateKey = kadbPrivateKeyFile();
        File dir = privateKey.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        com.flyfishxu.kadb.cert.KadbCert.INSTANCE.configure(
                new com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(privateKey.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new com.flyfishxu.kadb.cert.KadbCertPolicy(),
                Collections.emptyList());
        kadbCertConfigured = true;
    }

    private File kadbPrivateKeyFile() {
        return new File(new File(context.getFilesDir(), "kadb"), "adbkey.pem");
    }

    private AdbKeyPair readOrCreateDadbKeyPair() {
        File dir = new File(context.getFilesDir(), "adb");
        File privateKey = new File(dir, "adbkey");
        File publicKey = new File(dir, "adbkey.pub");
        if (!privateKey.isFile()) {
            AdbKeyPair.generate(privateKey, publicKey);
        }
        return AdbKeyPair.read(privateKey, publicKey);
    }

    private void ensurePhoneWifiReachable(String host) throws IOException {
        if (host == null || host.trim().isEmpty() || !isPrivateLanAddress(host.trim())) {
            return;
        }
        String phoneIp = phoneWifiIpv4();
        if (phoneIp.isEmpty()) {
            throw new IOException("Phone Wi-Fi is not connected. Connect this phone to the same Wi-Fi or hotspot as the glasses, then retry.");
        }
        if (!sameIpv4Slash24(phoneIp, host.trim())) {
            Log.d(TAG, "phone/glasses Wi-Fi subnet differs phone=" + redactHost(phoneIp)
                    + " glasses=" + redactHost(host));
        }
    }

    private String phoneWifiIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || !isWifiLikeInterface(networkInterface.getName())) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    String host = address.getHostAddress();
                    if (address instanceof Inet4Address && isPrivateLanAddress(host)) {
                        return host;
                    }
                }
            }
        } catch (Exception exception) {
            Log.d(TAG, "read phone Wi-Fi IP failed", exception);
        }
        return "";
    }

    private boolean isWifiLikeInterface(String name) {
        String value = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return value.equals("wlan0")
                || value.startsWith("wlan")
                || value.startsWith("ap")
                || value.contains("wifi")
                || value.contains("softap")
                || value.contains("swlan");
    }

    private boolean isPrivateLanAddress(String host) {
        if (host == null) {
            return false;
        }
        if (host.startsWith("192.168.") || host.startsWith("10.")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 172 && second >= 16 && second <= 31;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean sameIpv4Slash24(String a, String b) {
        String[] left = a == null ? new String[0] : a.split("\\.");
        String[] right = b == null ? new String[0] : b.split("\\.");
        return left.length == 4
                && right.length == 4
                && left[0].equals(right[0])
                && left[1].equals(right[1])
                && left[2].equals(right[2]);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String redactHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return "";
        }
        String value = host.trim();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot + 1) + "x" : "redacted";
    }

    private String shortMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }

    public static final class BootstrapResult {
        public final String pairHost;
        public final int pairPort;
        public final String connectHost;
        public final int connectPort;
        public final String output;

        BootstrapResult(String pairHost, int pairPort, String connectHost, int connectPort, String output) {
            this.pairHost = pairHost == null ? "" : pairHost;
            this.pairPort = pairPort;
            this.connectHost = connectHost == null ? "" : connectHost;
            this.connectPort = connectPort;
            this.output = output == null ? "" : output;
        }
    }
}
