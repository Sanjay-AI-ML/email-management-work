
package com.emailapp;

import com.emailapp.database.DatabaseConnection;
import java.sql.Connection;
import java.sql.SQLException;

public class App {

    public static void main(String[] args) {

        try (Connection connection =
                     DatabaseConnection.getConnection()) {

            System.out.println("Connected to MySQL successfully!");
            System.out.println("Database: " + connection.getCatalog());

        } catch (SQLException e) {
            System.out.println("Connection failed!");
            e.printStackTrace();
        }
    }
}
