package cc.coderm.cascade.idempotent.store;

import cc.coderm.cascade.idempotent.model.IdempotentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisIdempotentStoreTest {

    @Test
    void shouldReturnSuccessOnlyForStrictOccupiedPayload() {
        IdempotentStore.OccupyResult result =
                RedisIdempotentStore.parseTryOccupyResult("idem:test:1", List.of("1"));

        assertThat(result.occupied()).isTrue();
        assertThat(result.existingRecord()).isNull();
    }

    @Test
    void shouldReturnConflictWhenConflictPayloadIsValid() {
        IdempotentStore.OccupyResult result = RedisIdempotentStore.parseTryOccupyResult(
                "idem:test:2",
                List.of(
                        "0",
                        "state", "PROCESSING",
                        "scene", "order",
                        "owner", "owner-1",
                        "createdAt", "1",
                        "updatedAt", "1"
                )
        );

        assertThat(result.occupied()).isFalse();
        assertThat(result.existingRecord()).isNotNull();
        assertThat(result.existingRecord().getState()).isEqualTo(IdempotentState.PROCESSING);
        assertThat(result.existingRecord().getOwner()).isEqualTo("owner-1");
    }

    @Test
    void shouldFailClosedWhenPayloadIsNullOrEmpty() {
        assertMalformed(null);
        assertMalformed(List.of());
    }

    @Test
    void shouldFailClosedWhenMarkerIsUnknownOrMalformed() {
        assertMalformed(List.of("X"));
        assertMalformed(List.of("1", "state", "PROCESSING"));
        assertMalformed(List.of("0"));
        assertMalformed(List.of("0", "state", "PROCESSING", "owner"));
        assertMalformed(List.of("0", "state", "NOT_A_STATE"));
    }

    private static void assertMalformed(List<String> payload) {
        assertThatThrownBy(() -> RedisIdempotentStore.parseTryOccupyResult("idem:test:bad", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed tryOccupy Lua response");
    }
}
