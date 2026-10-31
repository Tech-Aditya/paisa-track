package com.paisatrack.modules.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class AccountIntegrationTest extends FinanceIntegrationTest {

    @Test
    void newUserGetsDefaultCashAccountInPreferredCurrency() throws Exception {
        Session session = register(uniqueEmail());
        mockMvc.perform(getAuthed("/api/v1/accounts", session.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Cash"))
                .andExpect(jsonPath("$.data[0].type").value("CASH"))
                .andExpect(jsonPath("$.data[0].currency").value("INR"))
                .andExpect(jsonPath("$.data[0].defaultAccount").value(true))
                .andExpect(jsonPath("$.data[0].currentBalance").value(0.00));

        String usdBody = mockMvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "email", uniqueEmail(), "password", PASSWORD, "fullName", "Sam", "defaultCurrency", "USD")))
                .andReturn().getResponse().getContentAsString();
        Session usd = new Session(UUID.fromString(JsonPath.read(usdBody, "$.data.user.id")),
                JsonPath.read(usdBody, "$.data.accessToken"), null);
        mockMvc.perform(getAuthed("/api/v1/accounts", usd.accessToken()))
                .andExpect(jsonPath("$.data[0].currency").value("USD"));
    }

    @Test
    void balanceIsOpeningPlusIncomeMinusExpense() throws Exception {
        Session session = register(uniqueEmail());
        UUID savings = createAccount(session, "HDFC Savings", "SAVINGS", "10000");

        income(session, "5000", category(session, "INC_SALARY"), "accountId", savings);
        expense(session, "1200.50", category(session, "GROCERIES"), "accountId", savings);

        assertThat(balance(session, savings)).isEqualByComparingTo("13799.50");
    }

    @Test
    void creditCardTracksAmountOwedAndBillPaymentIsNotAnExpense() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "20000");
        UUID card = createAccount(session, Map.of("name", "Visa", "type", "CREDIT_CARD",
                "creditLimit", new BigDecimal("50000"), "accountLast4", "4321"));

        expense(session, "2000", category(session, "SHOPPING_CLOTHES"), "accountId", card);
        mockMvc.perform(getAuthed("/api/v1/accounts/" + card, session.accessToken()))
                .andExpect(jsonPath("$.data.nature").value("LIABILITY"))
                .andExpect(jsonPath("$.data.currentBalance").value(2000.00))
                .andExpect(jsonPath("$.data.availableCredit").value(48000.00))
                .andExpect(jsonPath("$.data.accountLast4").value("4321"));

        transfer(session, "1500", bank, card);

        assertThat(balance(session, card)).isEqualByComparingTo("500");
        assertThat(balance(session, bank)).isEqualByComparingTo("18500");
        // The card purchase is the only expense; the bill payment is a transfer.
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", session.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(2000.00))
                .andExpect(jsonPath("$.data.currencies[0].expenseCount").value(1));
    }

    @Test
    void summaryKeepsCurrenciesSeparate() throws Exception {
        Session session = register(uniqueEmail());
        createAccount(session, "Bank", "SAVINGS", "1000");
        createAccount(session, Map.of("name", "Card", "type", "CREDIT_CARD", "openingBalance", new BigDecimal("300")));
        createAccount(session, Map.of("name", "US Bank", "type", "SAVINGS", "currency", "USD", "openingBalance", new BigDecimal("50")));

        mockMvc.perform(getAuthed("/api/v1/accounts/summary", session.accessToken()))
                .andExpect(jsonPath("$.data.currencies.length()").value(2))
                .andExpect(jsonPath("$.data.currencies[0].currency").value("INR"))
                .andExpect(jsonPath("$.data.currencies[0].totalAssets").value(1000.00))
                .andExpect(jsonPath("$.data.currencies[0].totalLiabilities").value(300.00))
                .andExpect(jsonPath("$.data.currencies[0].netWorth").value(700.00))
                .andExpect(jsonPath("$.data.currencies[1].currency").value("USD"))
                .andExpect(jsonPath("$.data.currencies[1].netWorth").value(50.00));
    }

    @Test
    void accountRulesAreEnforced() throws Exception {
        Session session = register(uniqueEmail());
        createAccount(session, "Wallet", "WALLET", "0");

        postAuthed("/api/v1/accounts", session, Map.of("name", "wallet", "type", "CASH"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_NAME"));
        postAuthed("/api/v1/accounts", session, Map.of("name", "Bank", "type", "SAVINGS", "creditLimit", 1000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("creditLimit"));
        postAuthed("/api/v1/accounts", session, Map.of("name", "Yen", "type", "CASH", "currency", "JPY",
                        "openingBalance", new BigDecimal("10.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("openingBalance"));
        postAuthed("/api/v1/accounts", session, Map.of("name", "Bad", "type", "SAVINGS", "accountLast4", "12345"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivationBlocksNewTransactionsButKeepsBalance() throws Exception {
        Session session = register(uniqueEmail());
        UUID cash = defaultAccount(session);
        UUID wallet = createAccount(session, "Paytm", "WALLET", "250");

        mockMvc.perform(authed(post("/api/v1/accounts/" + cash + "/deactivate"), session.accessToken()))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(authed(post("/api/v1/accounts/" + wallet + "/deactivate"), session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        List<String> activeIds = read("/api/v1/accounts", session, "$.data[*].id");
        assertThat(activeIds).doesNotContain(wallet.toString());
        assertThat(balance(session, wallet)).isEqualByComparingTo("250");

        postAuthed("/api/v1/expenses", session, body("amount", "10", "categoryId", category(session, "FOOD"), "accountId", wallet))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(authed(post("/api/v1/accounts/" + wallet + "/activate"), session.accessToken()))
                .andExpect(jsonPath("$.data.active").value(true));
        expense(session, "10", category(session, "FOOD"), "accountId", wallet);
    }

    @Test
    void makeDefaultSwitchesTheAccountUsedForQuickEntry() throws Exception {
        Session session = register(uniqueEmail());
        UUID cash = defaultAccount(session);
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");

        mockMvc.perform(authed(post("/api/v1/accounts/" + bank + "/make-default"), session.accessToken()))
                .andExpect(jsonPath("$.data.defaultAccount").value(true));
        assertThat(defaultAccount(session)).isEqualTo(bank);
        mockMvc.perform(getAuthed("/api/v1/accounts/" + cash, session.accessToken()))
                .andExpect(jsonPath("$.data.defaultAccount").value(false));

        expense(session, "99", category(session, "FOOD"));
        assertThat(balance(session, bank)).isEqualByComparingTo("-99");
    }

    @Test
    void openingBalanceEditMovesBalanceAndIsAudited() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "1000");
        expense(session, "100", category(session, "FOOD"), "accountId", bank);

        patchAuthed("/api/v1/accounts/" + bank, session, Map.of("openingBalance", new BigDecimal("1500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentBalance").value(1400.00));
    }

    @Test
    void accountsAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID aBank = createAccount(a, "Bank", "SAVINGS", "1000");

        mockMvc.perform(getAuthed("/api/v1/accounts/" + aBank, b.accessToken())).andExpect(status().isNotFound());
        patchAuthed("/api/v1/accounts/" + aBank, b, Map.of("name", "Hacked")).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/accounts/" + aBank + "/transactions", b.accessToken()))
                .andExpect(status().isNotFound());
        postAuthed("/api/v1/expenses", b, body("amount", "5", "categoryId", category(b, "FOOD"), "accountId", aBank))
                .andExpect(status().isNotFound());
        transfer(a, "10", aBank, defaultAccount(a));
        postAuthed("/api/v1/transfers", b, body("amount", "5", "fromAccountId", defaultAccount(b), "toAccountId", aBank))
                .andExpect(status().isNotFound());

        assertThat(balance(a, aBank)).isEqualByComparingTo("990");
    }
}
