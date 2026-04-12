package cc.coderm.cascade.idempotent.serializer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsupportedResultSerializerTest {

    @Test
    void shouldReturnNullPlaceholderWhenSerializingNull() {
        UnsupportedResultSerializer serializer = new UnsupportedResultSerializer();

        assertThat(serializer.serialize(null)).isEqualTo(serializer.nullPlaceholder());
    }

    @Test
    void shouldFailFastWhenSerializingNonNullResult() {
        UnsupportedResultSerializer serializer = new UnsupportedResultSerializer();

        assertThatThrownBy(() -> serializer.serialize(List.of("ok")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default ResultSerializer available");
    }

    @Test
    void shouldReturnNullWhenDeserializingNullPlaceholder() {
        UnsupportedResultSerializer serializer = new UnsupportedResultSerializer();

        assertThat(serializer.deserialize(serializer.nullPlaceholder(), String.class)).isNull();
    }

    @Test
    void shouldFailFastWhenDeserializingNonNullPayload() {
        UnsupportedResultSerializer serializer = new UnsupportedResultSerializer();

        assertThatThrownBy(() -> serializer.deserialize("{\"x\":1}", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default ResultSerializer available");
    }
}
