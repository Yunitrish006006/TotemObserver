package dev.totem.observer.bridge;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

/** Bounded local account store. All callers must run outside Minecraft/Netty event threads. */
public final class ObserverAccountStore {
    private static final int ITERATIONS = 600_000;
    private final Path file;
    private final Properties accounts = new Properties();
    private final SecureRandom random = new SecureRandom();

    public ObserverAccountStore(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (Files.exists(file)) {
            if (Files.size(file) > 32_768) throw new IOException("Account store too large");
            try (var reader = Files.newBufferedReader(file)) { accounts.load(reader); }
            if (!"1".equals(accounts.getProperty("version")) || accounts.size() > 65) throw new IOException("Invalid account store");
            for (String name : accounts.stringPropertyNames()) {
                if (name.equals("version")) continue;
                if (!validName(name)) throw new IOException("Invalid account record");
                decode(accounts.getProperty(name));
            }
        } else accounts.setProperty("version", "1");
    }

    public static boolean validName(String name) { return name != null && name.matches("[a-z0-9_]{3,24}") && !name.equals("version"); }
    public static boolean validPassword(char[] password) { return password.length >= 12 && password.length <= 128; }

    public synchronized boolean register(String name, char[] password) throws IOException {
        if (!validName(name) || !validPassword(password) || accounts.containsKey(name) || accounts.size() >= 65) return false;
        byte[] salt = new byte[16]; random.nextBytes(salt);
        byte[] hash = derive(password, salt);
        String record = Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        accounts.setProperty(name, record);
        try { save(); } catch (IOException failure) { accounts.remove(name); throw failure; }
        return true;
    }

    public synchronized boolean verify(String name, char[] password) throws IOException {
        if (!validName(name) || !validPassword(password)) return false;
        String record = accounts.getProperty(name);
        // Unknown accounts still perform the same work factor.
        byte[][] decoded = record == null ? new byte[][]{new byte[16], new byte[32]} : decode(record);
        byte[] actual = derive(password, decoded[0]);
        return MessageDigest.isEqual(actual, decoded[1]) && record != null;
    }

    private static byte[] derive(char[] password, byte[] salt) throws IOException {
        var spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IOException("Password hashing unavailable"); }
        finally { spec.clearPassword(); }
    }

    private static byte[][] decode(String record) throws IOException {
        try {
            String[] pieces = record.split(":", -1);
            if (pieces.length != 2) throw new IllegalArgumentException();
            byte[] salt = Base64.getDecoder().decode(pieces[0]);
            byte[] hash = Base64.getDecoder().decode(pieces[1]);
            if (salt.length != 16 || hash.length != 32) throw new IllegalArgumentException();
            return new byte[][]{salt, hash};
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid account record"); }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".observer-accounts-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            try (var writer = Files.newBufferedWriter(temporary)) { accounts.store(writer, "Observer accounts v1: PBKDF2-HMAC-SHA256, 600000 iterations"); }
            // Fail closed if atomic replacement is unavailable; never truncate the live store.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
