package ems.gmail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/** Owner-only OAuth cache. Tokens never belong in source control or the message database. */
final class TokenStore {
    private final Path directory;
    private final Path file;

    TokenStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.file = this.directory.resolve("gmail-token.json");
    }

    JsonObject load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        rejectSymlink(file);
        secure(directory, true);
        secure(file, false);
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException corrupt) {
            clear();
            return null;
        }
    }

    void save(JsonObject data) throws IOException {
        Files.createDirectories(directory);
        rejectSymlink(directory);
        secure(directory, true);
        Path temp = Files.createTempFile(directory, "oauth-", ".tmp");
        try {
            secure(temp, false);
            Files.writeString(temp, data.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            secure(file, false);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    void clear() throws IOException { Files.deleteIfExists(file); }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("OAuth cache must not be a symbolic link.");
    }

    private static void secure(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
            return;
        }
        throw new IOException("This filesystem cannot restrict access to OAuth credentials.");
    }
}
