package com.fastshare.app;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * Runs a real server program (wsgidav, impacket's smbserver) next to the tests. Where the program isn't installed the
 * tests are skipped; CI installs both with pip. Point FB_PYENV_BIN at the folder that holds them if they aren't on PATH.
 */
final class ExternalServer {
    private final Process process;
    final int port;

    private ExternalServer(Process p, int port) {
        this.process = p;
        this.port = port;
    }

    static File find(String tool) {
        String dir = System.getenv("FB_PYENV_BIN");
        if (dir != null && new File(dir, tool).canExecute()) return new File(dir, tool);
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                File f = new File(d, tool);
                if (f.canExecute()) return f;
            }
        }
        return null;
    }

    /** The python that has {@code module} installed, or null. Looks next to FB_PYENV_BIN first, then on PATH. */
    static String python(String module) {
        List<File> candidates = new java.util.ArrayList<>();
        String dir = System.getenv("FB_PYENV_BIN");
        if (dir != null) candidates.add(new File(dir, "python3"));
        File onPath = find("python3");
        if (onPath != null) candidates.add(onPath);
        for (File py : candidates) {
            if (!py.canExecute()) continue;
            try {
                Process p = new ProcessBuilder(py.getPath(), "-c", "import " + module).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) return py.getPath();
            } catch (Exception ignored) { }
        }
        return null;
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static ExternalServer start(List<String> command, int port, File log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        Process p = pb.start();
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive()) throw new IOException("server exited early, see " + log);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return new ExternalServer(p, port);
            } catch (IOException notYet) {
                Thread.sleep(150);
            }
        }
        p.destroyForcibly();
        throw new IOException("server didn't start, see " + log);
    }

    void stop() {
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException ignored) { }
    }
}
