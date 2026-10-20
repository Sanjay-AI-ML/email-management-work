package ems.gmail;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimpleJsonTest {
    @Test void rejectsIncompleteOrTrailingJsonRatherThanReturningPartialData() {
        for (String invalid : new String[] {"{\"id\":\"one\"", "[1", "\"open", "{} trailing", "{id:1}"}) {
            assertThrows(RuntimeException.class, () -> SimpleJson.parse(invalid), invalid);
        }
    }

    @Test void retainsTeammateHelperContractForUnicodeAndNumbers() {
        var object = SimpleJson.parseObject("{\"subject\":\"Hello \\u20ac\",\"count\":42,\"nested\":{\"ok\":true}}");
        assertEquals("Hello €", SimpleJson.getString(object, "subject"));
        assertEquals(42L, object.get("count"));
        assertEquals(Boolean.TRUE, SimpleJson.getObject(object, "nested").get("ok"));
        assertEquals("a\\n\\\"b", SimpleJson.escapeString("a\n\"b"));
    }
}
