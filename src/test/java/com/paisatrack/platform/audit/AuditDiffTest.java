package com.paisatrack.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AuditDiffTest {

    @Test
    void reportsOnlyChangedFieldsWithFromAndTo() {
        Map<String, Object> before = new HashMap<>();
        before.put("amount", "150");
        before.put("description", null);
        before.put("currency", "INR");
        Map<String, Object> after = new HashMap<>(before);
        after.put("amount", "250");
        after.put("description", "Lunch");

        Map<String, Object> diff = AuditService.diff(before, after);

        assertThat(diff).containsOnlyKeys("amount", "description");
        assertThat(diff.get("amount")).isEqualTo(Map.of("from", "150", "to", "250"));
        @SuppressWarnings("unchecked")
        Map<String, Object> description = (Map<String, Object>) diff.get("description");
        assertThat(description).containsEntry("from", null).containsEntry("to", "Lunch");
    }

    @Test
    void noChangesGiveAnEmptyDiff() {
        assertThat(AuditService.diff(Map.of("a", 1), Map.of("a", 1))).isEmpty();
    }
}
