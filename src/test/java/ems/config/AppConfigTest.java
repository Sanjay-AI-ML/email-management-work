package ems.config;

import ems.exception.AppException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @TempDir Path home;

    @Test void defaultsWorkWithoutSavingSecrets() throws Exception {
        AppConfig config = AppConfig.load(home, Map.of());
        assertTrue(config.getDatabaseUrl().contains("email_management"));
        assertEquals(home.resolve("config/credentials.json"), config.getGmailCredentialsPath());
        assertFalse(Files.exists(config.getConfigFile()));
    }

    @Test void environmentOverridesLocalFileIncludingEmptyPassword() throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/app.properties"),
                "db.user=local\ndb.password=local-secret\ngmail.credentials=custom/client.json\n");
        AppConfig config = AppConfig.load(home, Map.of("DB_USER", "environment", "DB_PASSWORD", ""));
        assertEquals("environment", config.getDatabaseUser());
        assertEquals("", config.getDatabasePassword());
        assertEquals(home.resolve("custom/client.json"), config.getGmailCredentialsPath());
    }

    @Test void invalidPropertiesBecomeActionableError() throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/app.properties"), "db.password=\\uBAD!\n");
        AppException exception = assertThrows(AppException.class, () -> AppConfig.load(home, Map.of()));
        assertFalse(exception.getMessage().contains("BAD!"));
        assertTrue(exception.getMessage().contains("config/app.properties"));
    }

    @Test void teammateCredentialFilenamesWorkWithDeterministicPrecedence() throws Exception {
        Files.writeString(home.resolve("client_secret.json"), "{}");
        assertEquals(home.resolve("client_secret.json"),
                AppConfig.load(home, Map.of()).getGmailCredentialsPath());
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/credentials.json"), "{}");
        assertEquals(home.resolve("config/credentials.json"),
                AppConfig.load(home, Map.of()).getGmailCredentialsPath());
        // A missing explicit path must report that path rather than use another client's grant.
        assertEquals(home.resolve("missing.json"), AppConfig.load(home,
                Map.of("GMAIL_CREDENTIALS", "missing.json")).getGmailCredentialsPath());
    }
}
