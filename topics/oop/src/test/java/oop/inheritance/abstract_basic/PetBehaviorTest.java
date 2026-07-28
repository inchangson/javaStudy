package oop.inheritance.abstract_basic;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PetBehaviorTest {
    @Test
    void dogBarks() {
        assertEquals("월월", printedBy(() -> new Dog().bark()));
    }

    @Test
    void catOverridesBarkAndEat() {
        Cat cat = new Cat();

        assertEquals("meow", printedBy(cat::bark));
        assertEquals("안 먹어", printedBy(cat::eat));
    }

    private static String printedBy(Runnable action) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(originalOut);
        }

        return output.toString(StandardCharsets.UTF_8).trim();
    }
}
