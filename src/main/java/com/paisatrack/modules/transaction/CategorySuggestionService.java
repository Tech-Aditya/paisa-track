package com.paisatrack.modules.transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.modules.user.UserPreferencesService;

/**
 * Suggests a category for a description or merchant. First from the user's own history (what they
 * chose before for similar words), then from a small list of well-known Indian merchants. Only a
 * suggestion: the user always picks.
 */
@Service
public class CategorySuggestionService {

    public record Suggestion(UUID categoryId, String name, String parentName,
                             /** HISTORY (you used it for similar entries) or KEYWORD (a known merchant). */ String reason) {
    }

    /** Reusable for many texts (e.g. a whole statement) with one history lookup. */
    public interface Matcher {
        Optional<Suggestion> best(String text);

        List<Suggestion> top(String text, int limit);
    }

    private static final Set<String> STOPWORDS = Set.of("the", "and", "for", "payment", "upi", "pos", "neft", "imps", "rtgs", "txn",
            "ref", "via", "bank", "transfer", "purchase", "from", "paid", "with", "debit", "credit", "card", "online", "india", "pvt",
            "ltd", "limited", "private", "www", "com", "ach", "nach", "mandate", "bill", "order");

    /** Known merchants and words, by built-in category code. */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("FOOD_RESTAURANTS", List.of("swiggy", "zomato", "restaurant", "dominos", "mcdonald", "kfc", "pizza", "burger", "eatsure", "biryani"));
        KEYWORDS.put("FOOD_TEA_COFFEE", List.of("starbucks", "chaayos", "coffee", "chai", "tea"));
        KEYWORDS.put("GROCERIES", List.of("bigbasket", "blinkit", "zepto", "grofers", "dmart", "grocery", "supermarket", "jiomart", "instamart", "kirana"));
        KEYWORDS.put("TRANSPORT_CAB", List.of("uber", "ola", "rapido", "taxi", "cab"));
        KEYWORDS.put("TRANSPORT_PETROL", List.of("petrol", "fuel", "hpcl", "bpcl", "indianoil", "iocl", "shell"));
        KEYWORDS.put("TRANSPORT_METRO", List.of("metro", "dmrc", "bmrcl"));
        KEYWORDS.put("TRANSPORT_TOLLS", List.of("fastag", "toll"));
        KEYWORDS.put("SHOPPING_ONLINE", List.of("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "snapdeal"));
        KEYWORDS.put("ENTERTAINMENT_OTT", List.of("netflix", "hotstar", "spotify", "primevideo", "sonyliv", "zee5", "jiocinema", "youtube"));
        KEYWORDS.put("ENTERTAINMENT_MOVIES", List.of("pvr", "inox", "bookmyshow", "cinema", "movie"));
        KEYWORDS.put("UTILITIES_ELECTRICITY", List.of("electricity", "bescom", "msedcl", "tneb", "tatapower", "bses", "power"));
        KEYWORDS.put("UTILITIES_MOBILE", List.of("airtel", "jio", "vodafone", "vi", "recharge", "prepaid", "postpaid"));
        KEYWORDS.put("UTILITIES_INTERNET", List.of("broadband", "fibernet", "wifi", "internet", "hathway"));
        KEYWORDS.put("UTILITIES_WATER", List.of("water"));
        KEYWORDS.put("HOUSING_RENT", List.of("rent", "landlord", "nobroker"));
        KEYWORDS.put("HEALTH_MEDICINES", List.of("pharmacy", "medical", "apollo", "medplus", "1mg", "pharmeasy", "netmeds", "chemist"));
        KEYWORDS.put("HEALTH_DOCTOR", List.of("hospital", "clinic", "doctor", "practo"));
        KEYWORDS.put("TRAVEL", List.of("irctc", "makemytrip", "goibibo", "indigo", "airindia", "vistara", "redbus", "cleartrip", "hotel", "oyo"));
        KEYWORDS.put("EDUCATION", List.of("school", "college", "tuition", "udemy", "coursera", "fees"));
        KEYWORDS.put("FINANCE_INSURANCE", List.of("insurance", "lic", "premium", "policybazaar"));
        KEYWORDS.put("PERSONAL_CARE", List.of("salon", "spa", "barber", "urbanclap", "urbancompany"));
        KEYWORDS.put("INC_SALARY", List.of("salary", "payroll"));
        KEYWORDS.put("INC_INTEREST", List.of("interest", "int.pd", "intpd"));
        KEYWORDS.put("INC_BONUS", List.of("bonus", "incentive"));
        KEYWORDS.put("INC_RENTAL", List.of("tenant"));
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final CategoryService categories;
    private final UserPreferencesService preferences;

    CategorySuggestionService(NamedParameterJdbcTemplate jdbc, CategoryService categories, UserPreferencesService preferences) {
        this.jdbc = jdbc;
        this.categories = categories;
        this.preferences = preferences;
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggest(UUID userId, String text, TransactionType type) {
        return matcher(userId, type).top(text, 3);
    }

    @Transactional(readOnly = true)
    public Matcher matcher(UUID userId, TransactionType type) {
        record Past(UUID categoryId, Set<String> tokens, long count) {
        }
        List<Past> history = jdbc.query("""
                select t.category_id, lower(coalesce(t.counterparty, '') || ' ' || coalesce(t.description, '')) as words, count(*) as n
                from transactions t join categories c on c.id = t.category_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.type = :type and t.loan_id is null
                  and c.active and t.transaction_date >= :since
                group by 1, 2 order by n desc limit 3000""",
                new MapSqlParameterSource().addValue("userId", userId).addValue("type", type.name())
                        .addValue("since", preferences.today(userId).minusYears(2)),
                (rs, row) -> new Past(rs.getObject("category_id", UUID.class), tokens(rs.getString("words")), rs.getLong("n")));
        Map<String, CategoryRef> builtIn = new HashMap<>();
        KEYWORDS.keySet().stream()
                .filter(code -> (type == TransactionType.INCOME) == code.startsWith("INC_"))
                .forEach(code -> categories.findActiveBySystemCode(userId, code).ifPresent(ref -> builtIn.put(code, ref)));
        Map<UUID, CategoryRef> refs = categories.refs(userId, history.stream().map(Past::categoryId).collect(Collectors.toSet()));

        return new Matcher() {
            @Override
            public Optional<Suggestion> best(String text) {
                List<Suggestion> top = top(text, 1);
                return top.isEmpty() ? Optional.empty() : Optional.of(top.getFirst());
            }

            @Override
            public List<Suggestion> top(String text, int limit) {
                Set<String> words = tokens(text);
                if (words.isEmpty()) {
                    return List.of();
                }
                Map<UUID, Double> scores = new HashMap<>();
                for (Past p : history) {
                    long shared = p.tokens().stream().filter(words::contains).count();
                    if (shared > 0) {
                        scores.merge(p.categoryId(), (double) shared * p.count(), Double::sum);
                    }
                }
                List<Suggestion> out = new ArrayList<>();
                scores.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                        .map(e -> refs.get(e.getKey()))
                        .filter(ref -> ref != null && ref.active())
                        .forEach(ref -> out.add(new Suggestion(ref.id(), ref.name(), ref.parentName(), "HISTORY")));
                String joined = String.join(" ", words);
                KEYWORDS.forEach((code, keys) -> {
                    CategoryRef ref = builtIn.get(code);
                    if (ref != null && keys.stream().anyMatch(k -> words.contains(k) || (k.length() > 4 && joined.contains(k)))
                            && out.stream().noneMatch(s -> s.categoryId().equals(ref.id()))) {
                        out.add(new Suggestion(ref.id(), ref.name(), ref.parentName(), "KEYWORD"));
                    }
                });
                return out.size() > limit ? out.subList(0, limit) : out;
            }
        };
    }

    /** Lowercase words of 2+ letters, without banking noise and numbers. */
    static Set<String> tokens(String text) {
        if (text == null) {
            return Set.of();
        }
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}.]+"))
                .map(w -> w.replaceAll("^\\.+|\\.+$", ""))
                .filter(w -> w.length() >= 2 && !STOPWORDS.contains(w) && !w.matches("[\\d.]+"))
                .collect(Collectors.toSet());
    }
}
