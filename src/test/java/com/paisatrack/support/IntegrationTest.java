package com.paisatrack.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Full app against PostgreSQL. Each test uses fresh unique emails, so no cleanup is needed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Secret123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JsonMapper jsonMapper;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.ConnectionInfo db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected String json(Object body) {
        return jsonMapper.writeValueAsString(body);
    }

    protected MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    protected static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String accessToken) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    protected static MockHttpServletRequestBuilder getAuthed(String url, String accessToken) {
        return authed(get(url), accessToken);
    }

    protected Session register(String email) throws Exception {
        return register(email, PASSWORD, "Test User");
    }

    protected Session register(String email, String password, String fullName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("fullName", fullName);
        MvcResult result = mockMvc.perform(postJson("/api/v1/auth/register", body))
                .andExpect(status().isCreated())
                .andReturn();
        return Session.from(result.getResponse().getContentAsString());
    }

    protected Session login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", password)))
                .andExpect(status().isOk())
                .andReturn();
        return Session.from(result.getResponse().getContentAsString());
    }

    /** Tokens and id from an AuthResponse envelope. */
    public record Session(UUID userId, String accessToken, String refreshToken) {
        static Session from(String responseBody) {
            return new Session(
                    UUID.fromString(JsonPath.read(responseBody, "$.data.user.id")),
                    JsonPath.read(responseBody, "$.data.accessToken"),
                    JsonPath.read(responseBody, "$.data.refreshToken"));
        }
    }
}
