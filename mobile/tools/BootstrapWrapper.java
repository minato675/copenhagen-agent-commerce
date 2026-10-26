import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Downloads only the official pinned Wrapper JAR; no custom Gradle implementation. JDK 17+. */
class BootstrapWrapper {
    static final String SHA = "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046";
    static final String URL = "https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar";
    static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) digest.update(block, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path wrapper = root.resolve("gradle/wrapper/gradle-wrapper.jar");
        Files.createDirectories(wrapper.getParent());
        try (FileChannel channel = FileChannel.open(wrapper.resolveSibling("bootstrap.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            if (Files.exists(wrapper)) {
                if (!hash(wrapper).equals(SHA)) throw new SecurityException("Wrapper JAR checksum mismatch. Remove the untrusted JAR before retrying.");
            } else {
                System.out.println("Downloading official Gradle 8.11.1 Wrapper JAR...");
                Path temp = Files.createTempFile(wrapper.getParent(), "wrapper-", ".part");
                try {
                    URLConnection conn = URI.create(URL).toURL().openConnection();
                    conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
                    try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(temp)) {
                        byte[] bytes = in.readNBytes(1_048_577);
                        if (bytes.length > 1_048_576) throw new IOException("Wrapper JAR exceeds size limit");
                        out.write(bytes);
                    }
                    if (!hash(temp).equals(SHA)) throw new SecurityException("Downloaded Wrapper JAR checksum mismatch");
                    Files.move(temp, wrapper, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temp); }
            }
        }
        System.out.println("Official Gradle Wrapper JAR verified.");
    }
}
