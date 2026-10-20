package ems.config;

import ems.exception.AppException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Local-only configuration; environment values override an optional properties file. */
public final class AppConfig {
    private final Path home;
    private final Path configFile;
    private final Properties properties;
    private final Map<String, String> environment;

    private AppConfig(Path home, Path configFile, Properties properties, Map<String, String> environment) {
        this.home = home.toAbsolutePath().normalize();
        this.configFile = configFile.toAbsolutePath().normalize();
        this.properties = properties;
        this.environment = Map.copyOf(environment);
    }

    public static AppConfig load() throws AppException {
        return load(Path.of(System.getProperty("maildesk.home", ".")), System.getenv());
    }

    public static AppConfig load(Path home, Map<String, String> environment) throws AppException {
        Path normalized = home.toAbsolutePath().normalize();
        Path file = normalized.resolve("config/app.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException exception) {
                throw new AppException("Cannot read config/app.properties. Check the local file format and permissions.", exception);
            }
        }
        return new AppConfig(normalized, file, properties, environment);
    }

    private String value(String environmentKey, String propertyKey, String fallback) {
        return environment.containsKey(environmentKey) ? environment.get(environmentKey)
                : properties.getProperty(propertyKey, fallback);
    }

    public String getDatabaseUrl() {
        return value("DB_URL", "db.url", "jdbc:mysql://localhost:3306/email_management"
                + "?createDatabaseIfNotExist=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&connectTimeout=5000&socketTimeout=15000").strip();
    }

    public String getDatabaseUser() { return value("DB_USER", "db.user", "root").strip(); }
    public String getDatabasePassword() { return value("DB_PASSWORD", "db.password", ""); }
    public Path getGmailCredentialsPath() {
        if (environment.containsKey("GMAIL_CREDENTIALS") || properties.containsKey("gmail.credentials")) {
            return localPath(value("GMAIL_CREDENTIALS", "gmail.credentials", "config/credentials.json"));
        }
        // Accept the filenames documented by Member 2, with the local config taking precedence.
        for (String candidate : new String[] {"config/credentials.json", "config/client_secret.json",
                "credentials.json", "client_secret.json", "client_secrets.json"}) {
            Path path = home.resolve(candidate);
            if (Files.isRegularFile(path)) return path;
        }
        return home.resolve("config/credentials.json");
    }
    public Path getTokenDirectory() {
        return localPath(value("GMAIL_TOKEN_DIR", "gmail.tokenDir", "config/tokens"));
    }
    public Path getConfigFile() { return configFile; }
    public Path getHome() { return home; }

    private Path localPath(String value) {
        Path path = Path.of(value.strip());
        return (path.isAbsolute() ? path : home.resolve(path)).normalize();
    }
}
