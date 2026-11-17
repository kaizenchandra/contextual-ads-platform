package io.contextads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

class DomainTest {
    @Test
    void topicsChange() {
        var c = new Classifier();
        assertEquals("automotive.ev", c.analyze("electric vehicle charging battery").category());
        assertEquals("technology.java", c.analyze("java spring jvm developer").category());
    }

    @Test
    void scriptsExcluded() {
        assertEquals("unknown", new Classifier().analyze("<script>java spring jvm</script>hello").category());
    }

    @Test
    void unsafeFails() {
        assertFalse(new Classifier().analyze("electric vehicle violence").safe());
    }

    @Test
    void tokensBoundToEvent() {
        var t = new Tokens("123456789012345678901234567890123");
        var id = UUID.randomUUID();
        var s = t.sign(id, "click");
        assertEquals(id, t.verify(s, "click"));
        assertThrows(IllegalArgumentException.class, () -> t.verify(s, "impression"));
    }

    @Test
    void destination() {
        assertThrows(IllegalArgumentException.class, () -> Platform.destination("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> Platform.destination("https://example.org/?secret=x"));
    }
}
