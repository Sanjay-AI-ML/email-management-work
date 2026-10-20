package com.emailapp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppTest {
    @Test void teammateLauncherUsesProductionHelp() {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            App.main(new String[] {"--help"});
        } finally { System.setOut(previous); }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("--check-setup"));
    }
}
