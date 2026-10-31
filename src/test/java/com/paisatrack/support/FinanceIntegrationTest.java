package com.paisatrack.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Helpers for account, category and transaction tests. Amounts are passed as strings to keep exact decimals. */
public abstract class FinanceIntegrationTest extends IntegrationTest {

    protected String getJson(String url, Session session) throws Exception {
        return mockMvc.perform(getAuthed(url, session.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    protected <T> T read(String url, Session session, String jsonPath) throws Exception {
        return JsonPath.read(getJson(url, session), jsonPath);
    }

    /** Id of a seeded category by its stable system code, e.g. FOOD_LUNCH. */
    protected UUID category(Session session, String systemCode) throws Exception {
        List<String> ids = read("/api/v1/categories?includeInactive=true", session,
                "$..[?(@.systemCode == '" + systemCode + "')].id");
        return UUID.fromString(ids.getFirst());
    }

    protected UUID defaultAccount(Session session) throws Exception {
        List<String> ids = read("/api/v1/accounts", session, "$.data[?(@.defaultAccount == true)].id");
        return UUID.fromString(ids.getFirst());
    }

    protected UUID createAccount(Session session, Map<String, Object> body) throws Exception {
        String json = mockMvc.perform(authed(post("/api/v1/accounts"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.data.id"));
    }

    protected UUID createAccount(Session session, String name, String type, String openingBalance) throws Exception {
        return createAccount(session, Map.of("name", name, "type", type, "openingBalance", new BigDecimal(openingBalance)));
    }

    protected BigDecimal balance(Session session, UUID accountId) throws Exception {
        Object value = read("/api/v1/accounts/" + accountId, session, "$.data.currentBalance");
        return new BigDecimal(value.toString());
    }

    protected ResultActions postAuthed(String url, Session session, Object body) throws Exception {
        return mockMvc.perform(authed(post(url), session.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions putAuthed(String url, Session session, Object body) throws Exception {
        return mockMvc.perform(authed(put(url),
                session.accessToken()).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions patchAuthed(String url, Session session, Object body) throws Exception {
        return mockMvc.perform(authed(patch(url), session.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected UUID createdId(ResultActions result) throws Exception {
        String json = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.data.id"));
    }

    /** Builds a request body from key/value pairs, skipping null values. */
    protected static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1] instanceof String s && looksNumeric(s)
                        ? new BigDecimal(s) : keyValues[i + 1]);
            }
        }
        return map;
    }

    protected UUID expense(Session session, String amount, UUID categoryId, Object... extra) throws Exception {
        Map<String, Object> b = body("amount", amount, "categoryId", categoryId);
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/expenses", session, b));
    }

    protected UUID income(Session session, String amount, UUID categoryId, Object... extra) throws Exception {
        Map<String, Object> b = body("amount", amount, "categoryId", categoryId);
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/income", session, b));
    }

    protected UUID transfer(Session session, String amount, UUID from, UUID to, Object... extra) throws Exception {
        Map<String, Object> b = body("amount", amount, "fromAccountId", from, "toAccountId", to);
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/transfers", session, b));
    }

    private static boolean looksNumeric(String s) {
        return s.matches("-?\\d+(\\.\\d+)?");
    }
}
