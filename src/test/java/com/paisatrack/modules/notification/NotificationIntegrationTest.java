package com.paisatrack.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.paisatrack.support.FinanceIntegrationTest;

class NotificationIntegrationTest extends FinanceIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    NotificationJob job;

    private static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    private int refresh(Session s) throws Exception {
        String json = mockMvc.perform(authed(post("/api/v1/notifications/refresh"), s.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Integer>read(json, "$.data.created");
    }

    private List<Map<String, Object>> notifications(Session s) throws Exception {
        return read("/api/v1/notifications?size=100", s, "$.data.items");
    }

    private ResultActions putPreferences(Session s, Object... items) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object item : items) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) item;
            list.add(m);
        }
        return mockMvc.perform(authed(put("/api/v1/notifications/preferences"), s.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("preferences", list))));
    }

    private UUID bill(Session s, String name, String kind, LocalDate start) throws Exception {
        return createdId(postAuthed("/api/v1/recurring-transactions", s, body("name", name, "kind", kind, "type", "EXPENSE",
                "amount", "999", "categoryId", category(s, "UTILITIES_INTERNET"), "frequency", "MONTHLY", "startDate", start.toString())));
    }

    @Test
    void remindersCoverEveryTypeAndAreNeverDuplicated() throws Exception {
        Session s = register(uniqueEmail());
        postAuthed("/api/v1/loans", s, body("name", "Car loan", "type", "VEHICLE", "principal", "500000", "annualRate", "10.5",
                "tenureMonths", 60, "firstEmiDate", today().plusDays(2).toString())).andExpect(status().isCreated());
        bill(s, "Broadband", "BILL", today());
        bill(s, "Netflix", "SUBSCRIPTION", today().plusDays(1));
        postAuthed("/api/v1/lending", s, body("personName", "Rahul", "amount", "2000", "dueDate", today().toString()))
                .andExpect(status().isCreated());
        postAuthed("/api/v1/borrowing", s, body("personName", "Priya", "amount", "500", "startDate", today().minusDays(10).toString(),
                "dueDate", today().minusDays(1).toString())).andExpect(status().isCreated());
        postAuthed("/api/v1/budgets", s, body("scope", "OVERALL", "period", "MONTHLY", "amount", "1000")).andExpect(status().isCreated());
        expense(s, "800", category(s, "FOOD_LUNCH"));
        postAuthed("/api/v1/income-sources", s, body("name", "Acme salary", "type", "SALARY", "expectedAmount", "90000",
                "expectedDay", today().plusDays(1).getDayOfMonth(), "startMonth", today().plusDays(1).toString().substring(0, 7)))
                .andExpect(status().isCreated());

        int created = refresh(s);
        assertThat(created).isEqualTo(7);
        List<Map<String, Object>> all = notifications(s);
        assertThat(all).extracting(n -> n.get("type") + ":" + n.get("stage")).containsExactlyInAnyOrder(
                "EMI:UPCOMING", "BILL:DUE", "SUBSCRIPTION:UPCOMING", "LENDING:DUE", "BORROWING:OVERDUE", "BUDGET:ALERT",
                "SALARY:UPCOMING");
        assertThat(all).filteredOn(n -> "BUDGET".equals(n.get("type"))).extracting(n -> n.get("title"))
                .containsExactly("Monthly budget 75% used");
        assertThat(all).filteredOn(n -> "LENDING".equals(n.get("type"))).extracting(n -> n.get("title"))
                .containsExactly("Rahul owes you ₹2,000.00");

        // Retries, the scheduled job and parallel refreshes add nothing.
        assertThat(refresh(s)).isZero();
        job.run();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return refresh(s);
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                assertThat(run.get()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(notifications(s)).hasSize(7);

        // Crossing the next threshold is a new alert.
        expense(s, "300", category(s, "FOOD_LUNCH"));
        assertThat(refresh(s)).isEqualTo(1);
        assertThat(notifications(s).getFirst().get("title")).isEqualTo("Monthly budget exceeded");
    }

    @Test
    void resolvedItemsStopReminding() throws Exception {
        Session s = register(uniqueEmail());
        UUID rule = bill(s, "Broadband", "BILL", today());
        String occurrence = read("/api/v1/recurring-transactions/" + rule + "/occurrences", s, "$.data[-1].id");
        postAuthed("/api/v1/recurring-occurrences/" + occurrence + "/confirm", s, Map.of()).andExpect(status().isOk());
        assertThat(refresh(s)).isZero();
    }

    @Test
    void preferencesTurnTypesOffAndSetTheWindow() throws Exception {
        Session s = register(uniqueEmail());
        bill(s, "Broadband", "BILL", today().plusDays(5));
        mockMvc.perform(getAuthed("/api/v1/notifications/preferences", s.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(NotificationType.values().length)))
                .andExpect(jsonPath("$.data[?(@.type == 'BILL')].daysBefore").value(3))
                .andExpect(jsonPath("$.data[?(@.type == 'BILL')].enabled").value(true));
        assertThat(refresh(s)).isZero();

        putPreferences(s, Map.of("type", "BILL", "daysBefore", 7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.type == 'BILL')].daysBefore").value(7));
        assertThat(refresh(s)).isEqualTo(1);

        bill(s, "Electricity", "BILL", today());
        putPreferences(s, Map.of("type", "BILL", "enabled", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.type == 'BILL')].daysBefore").value(7));
        assertThat(refresh(s)).isZero();
        putPreferences(s, Map.of("type", "BILL", "daysBefore", 31)).andExpect(status().isBadRequest());
    }

    @Test
    void dueDatesFollowTheUsersTimezone() throws Exception {
        Session s = register(uniqueEmail());
        setTimezone(s, "Pacific/Pago_Pago");
        bill(s, "Rent", "RENT", LocalDate.now(ZoneId.of("Pacific/Pago_Pago")));
        assertThat(refresh(s)).isEqualTo(1);
        assertThat(notifications(s).getFirst().get("stage")).isEqualTo("DUE");

        // Same moment, but it's already the next day in Kiritimati (UTC+14): the bill is now overdue.
        setTimezone(s, "Pacific/Kiritimati");
        assertThat(refresh(s)).isEqualTo(1);
        assertThat(notifications(s).getFirst().get("stage")).isEqualTo("OVERDUE");
        assertThat(refresh(s)).isZero();
    }

    @Test
    void readStateAndIsolation() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        bill(a, "Broadband", "BILL", today());
        bill(a, "Netflix", "SUBSCRIPTION", today());
        refresh(a);
        String first = (String) notifications(a).getFirst().get("id");

        mockMvc.perform(getAuthed("/api/v1/notifications/unread-count", a.accessToken())).andExpect(jsonPath("$.data.unread").value(2));
        mockMvc.perform(authed(post("/api/v1/notifications/" + first + "/read"), b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/notifications", b.accessToken())).andExpect(jsonPath("$.data.totalItems").value(0));

        mockMvc.perform(authed(post("/api/v1/notifications/" + first + "/read"), a.accessToken()))
                .andExpect(jsonPath("$.data.read").value(true));
        mockMvc.perform(authed(post("/api/v1/notifications/" + first + "/read"), a.accessToken())).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/notifications?unreadOnly=true", a.accessToken()))
                .andExpect(jsonPath("$.data.items", hasSize(1)));
        mockMvc.perform(authed(post("/api/v1/notifications/read-all"), a.accessToken())).andExpect(jsonPath("$.data.marked").value(1));
        mockMvc.perform(getAuthed("/api/v1/notifications/unread-count", a.accessToken())).andExpect(jsonPath("$.data.unread").value(0));
    }

    private void setTimezone(Session s, String zone) throws Exception {
        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), s.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("timezone", zone))))
                .andExpect(status().isOk());
    }
}
