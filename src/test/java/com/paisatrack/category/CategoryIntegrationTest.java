package com.paisatrack.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class CategoryIntegrationTest extends FinanceIntegrationTest {

    @Test
    void newUserGetsDefaultExpenseAndIncomeCategories() throws Exception {
        Session session = register(uniqueEmail());

        List<String> expenseNames = read("/api/v1/categories?type=EXPENSE", session, "$.data[*].name");
        assertThat(expenseNames).contains("Food & Dining", "Groceries", "Transportation", "Utilities", "Housing")
                .doesNotContain("Investments");
        List<String> foodChildren = read("/api/v1/categories?type=EXPENSE", session,
                "$.data[?(@.systemCode == 'FOOD')].children[*].name");
        assertThat(foodChildren).containsExactly("Breakfast", "Lunch", "Dinner", "Snacks", "Restaurants", "Tea & Coffee");
        List<String> incomeNames = read("/api/v1/categories?type=INCOME", session, "$.data[*].name");
        assertThat(incomeNames).contains("Salary", "Freelance", "Interest Income");
        // No EMI categories: loan principal is not a consumption expense.
        List<String> all = read("/api/v1/categories", session, "$..name");
        assertThat(all).noneMatch(name -> name.contains("EMI"));
    }

    @Test
    void createTopLevelAndSubcategories() throws Exception {
        Session session = register(uniqueEmail());

        postAuthed("/api/v1/categories", session, Map.of("name", "Pets"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("type"));
        UUID pets = createdId(postAuthed("/api/v1/categories", session, Map.of("name", "Pets", "type", "EXPENSE")));
        UUID food = createdId(postAuthed("/api/v1/categories", session, Map.of("name", "Pet Food", "parentId", pets.toString())));

        mockMvc.perform(getAuthed("/api/v1/categories/" + food, session.accessToken()))
                .andExpect(jsonPath("$.data.type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.parentId").value(pets.toString()));
        postAuthed("/api/v1/categories", session, Map.of("name", "Kibble", "parentId", food.toString()))
                .andExpect(status().isUnprocessableContent());
        postAuthed("/api/v1/categories", session, Map.of("name", "Bonus", "type", "INCOME", "parentId", pets.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void siblingNamesAreUniqueIgnoringCase() throws Exception {
        Session session = register(uniqueEmail());
        UUID food = category(session, "FOOD");
        UUID travel = category(session, "TRAVEL");

        postAuthed("/api/v1/categories", session, Map.of("name", "LUNCH", "parentId", food.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_NAME"));
        createdId(postAuthed("/api/v1/categories", session, Map.of("name", "Lunch", "parentId", travel.toString())));
    }

    @Test
    void renameKeepsTransactionsLinked() throws Exception {
        Session session = register(uniqueEmail());
        UUID lunch = category(session, "FOOD_LUNCH");
        UUID expense = expense(session, "150", lunch);

        patchAuthed("/api/v1/categories/" + lunch, session, Map.of("name", "Office Lunch"))
                .andExpect(status().isOk());

        mockMvc.perform(getAuthed("/api/v1/transactions/" + expense, session.accessToken()))
                .andExpect(jsonPath("$.data.category.id").value(lunch.toString()))
                .andExpect(jsonPath("$.data.category.name").value("Office Lunch"))
                .andExpect(jsonPath("$.data.category.parentName").value("Food & Dining"));
    }

    @Test
    void deactivatedCategoryCantTakeNewTransactionsButHistoryStays() throws Exception {
        Session session = register(uniqueEmail());
        UUID food = category(session, "FOOD");
        UUID lunch = category(session, "FOOD_LUNCH");
        UUID existing = expense(session, "150", lunch);

        mockMvc.perform(authed(post("/api/v1/categories/" + food + "/deactivate"), session.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/categories/" + lunch, session.accessToken()))
                .andExpect(jsonPath("$.data.active").value(false));
        postAuthed("/api/v1/expenses", session, body("amount", "10", "categoryId", lunch))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(getAuthed("/api/v1/transactions/" + existing, session.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/v1/categories/" + food + "/activate"), session.accessToken()))
                .andExpect(status().isOk());
        expense(session, "10", lunch);
    }

    @Test
    void onlyUnusedLeafCategoriesCanBeDeleted() throws Exception {
        Session session = register(uniqueEmail());
        UUID lunch = category(session, "FOOD_LUNCH");
        expense(session, "150", lunch);

        mockMvc.perform(authed(delete("/api/v1/categories/" + lunch), session.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_IN_USE"));
        mockMvc.perform(authed(delete("/api/v1/categories/" + category(session, "TRANSPORT")), session.accessToken()))
                .andExpect(status().isConflict());
        mockMvc.perform(authed(delete("/api/v1/categories/" + category(session, "TRANSPORT_TOLLS")), session.accessToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void categoriesAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID aLunch = category(a, "FOOD_LUNCH");

        mockMvc.perform(getAuthed("/api/v1/categories/" + aLunch, b.accessToken())).andExpect(status().isNotFound());
        patchAuthed("/api/v1/categories/" + aLunch, b, Map.of("name", "x")).andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/categories/" + aLunch), b.accessToken())).andExpect(status().isNotFound());
        postAuthed("/api/v1/expenses", b, body("amount", "5", "categoryId", aLunch)).andExpect(status().isNotFound());
        assertThat(category(b, "FOOD_LUNCH")).isNotEqualTo(aLunch);
    }
}
