package io.github.cascade.cache.v2.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationParserTest {

    @Test
    void shouldParseCommonDurationUnits() {
        assertEquals(1800, DurationParser.parseToSeconds("30m", 0));
        assertEquals(300, DurationParser.parseToSeconds("5m", 0));
        assertEquals(90, DurationParser.parseToSeconds("90s", 0));
        assertEquals(3600, DurationParser.parseToSeconds("1h", 0));
        assertEquals(86400, DurationParser.parseToSeconds("1d", 0));
    }
}
