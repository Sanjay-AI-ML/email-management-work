package ems.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Opens one owned connection per store operation. */
@FunctionalInterface
public interface ConnectionFactory {
    Connection open() throws SQLException;
}
