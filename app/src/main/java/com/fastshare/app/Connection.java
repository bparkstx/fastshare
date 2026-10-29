package com.fastshare.app;


/** One saved network location. */
public final class Connection {

    public enum Type {
        FTP("FTP", 21, "ftp"),
        FTPS("FTP over TLS", 21, "ftps"),
        SFTP("SFTP (SSH)", 22, "sftp"),
        SMB("Windows share (SMB)", 445, "smb"),
        WEBDAV("WebDAV", 80, "http"),
        WEBDAVS("WebDAV (secure)", 443, "https");

        public final String label;
        public final int defaultPort;
        public final String scheme;

        Type(String label, int defaultPort, String scheme) {
            this.label = label;
            this.defaultPort = defaultPort;
            this.scheme = scheme;
        }
    }

    public String id = "";
    public String name = "";
    public Type type = Type.SFTP;
    public String host = "";
    public int port;
    public String user = "";
    public String password = "";
    /** SMB: the share name (and optional folder). Others: the folder everything starts from. */
    public String path = "";
    /** SFTP only: a private key in PEM text. The password then unlocks the key. */
    public String privateKey = "";
    /** Accept a server certificate that isn't signed by a known authority (self-signed NAS, home server). */
    public boolean trustAll;
    /** SFTP: fingerprint of the server's key, remembered the first time and checked on every later connect. */
    public String hostKey = "";

    public Connection copy() {
        Connection c = new Connection();
        c.id = id;
        c.name = name;
        c.type = type;
        c.host = host;
        c.port = port;
        c.user = user;
        c.password = password;
        c.path = path;
        c.privateKey = privateKey;
        c.trustAll = trustAll;
        c.hostKey = hostKey;
        return c;
    }

    public int portOrDefault() {
        return port > 0 ? port : type.defaultPort;
    }

    /** "sftp://me@192.168.1.5:22/Photos" for showing under the name. */
    public String describe() {
        StringBuilder sb = new StringBuilder(type.scheme).append("://");
        if (!user.isEmpty()) sb.append(user).append('@');
        sb.append(host);
        if (port > 0 && port != type.defaultPort) sb.append(':').append(port);
        String p = path.replace('\\', '/');
        if (!p.isEmpty()) sb.append(p.startsWith("/") ? p : "/" + p);
        return sb.toString();
    }

    /** What is wrong with the details, or null when they look usable. */
    public String problem() {
        if (host.trim().isEmpty()) return "Enter the server's address.";
        if (host.contains("/") || host.contains(" ")) return "The address should be just a name or number, like 192.168.1.20 or nas.local.";
        if (port < 0 || port > 65535) return "The port should be between 1 and 65535.";
        if (type == Type.SMB && path.replace('\\', '/').replaceAll("^/+", "").isEmpty()) return "Enter the name of the shared folder, as it appears on the computer.";
        if (type == Type.SFTP && user.trim().isEmpty()) return "Enter the user name.";
        return null;
    }

    @Override public String toString() {
        return name.isEmpty() ? host : name;
    }
}
