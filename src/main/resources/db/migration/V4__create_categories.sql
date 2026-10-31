-- Categories: two levels (category -> subcategory), per user, typed EXPENSE or INCOME.
-- category_templates is the single source of the defaults every user receives.

CREATE TABLE category_templates (
    code         VARCHAR(40) PRIMARY KEY,
    parent_code  VARCHAR(40) REFERENCES category_templates (code),
    type         VARCHAR(10) NOT NULL CHECK (type IN ('EXPENSE', 'INCOME')),
    name         VARCHAR(60) NOT NULL,
    sort_order   INT         NOT NULL
);

CREATE TABLE categories (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id    UUID        REFERENCES categories (id),
    system_code  VARCHAR(40),  -- stable id of a seeded default; survives renames
    type         VARCHAR(10) NOT NULL,
    name         VARCHAR(60) NOT NULL,
    sort_order   INT         NOT NULL DEFAULT 0,
    active       BOOLEAN     NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_categories_type CHECK (type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT ck_categories_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_categories_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE UNIQUE INDEX uk_categories_sibling_name ON categories (user_id, parent_id, lower(name)) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX uk_categories_system_code ON categories (user_id, system_code) WHERE system_code IS NOT NULL;
CREATE INDEX ix_categories_parent ON categories (parent_id);

-- EMIs, loan principal and investments are deliberately absent: they are not
-- consumption expenses and get their own transaction types in later phases.
INSERT INTO category_templates (code, parent_code, type, name, sort_order) VALUES
    ('FOOD',                 NULL,             'EXPENSE', 'Food & Dining', 1),
    ('FOOD_BREAKFAST',       'FOOD',           'EXPENSE', 'Breakfast', 1),
    ('FOOD_LUNCH',           'FOOD',           'EXPENSE', 'Lunch', 2),
    ('FOOD_DINNER',          'FOOD',           'EXPENSE', 'Dinner', 3),
    ('FOOD_SNACKS',          'FOOD',           'EXPENSE', 'Snacks', 4),
    ('FOOD_RESTAURANTS',     'FOOD',           'EXPENSE', 'Restaurants', 5),
    ('FOOD_TEA_COFFEE',      'FOOD',           'EXPENSE', 'Tea & Coffee', 6),
    ('GROCERIES',            NULL,             'EXPENSE', 'Groceries', 2),
    ('GROCERIES_VEGETABLES', 'GROCERIES',      'EXPENSE', 'Vegetables', 1),
    ('GROCERIES_FRUITS',     'GROCERIES',      'EXPENSE', 'Fruits', 2),
    ('GROCERIES_DAIRY',      'GROCERIES',      'EXPENSE', 'Dairy', 3),
    ('GROCERIES_HOUSEHOLD',  'GROCERIES',      'EXPENSE', 'Household Supplies', 4),
    ('TRANSPORT',            NULL,             'EXPENSE', 'Transportation', 3),
    ('TRANSPORT_PETROL',     'TRANSPORT',      'EXPENSE', 'Petrol', 1),
    ('TRANSPORT_DIESEL',     'TRANSPORT',      'EXPENSE', 'Diesel', 2),
    ('TRANSPORT_BUS',        'TRANSPORT',      'EXPENSE', 'Bus', 3),
    ('TRANSPORT_METRO',      'TRANSPORT',      'EXPENSE', 'Metro', 4),
    ('TRANSPORT_CAB',        'TRANSPORT',      'EXPENSE', 'Cab', 5),
    ('TRANSPORT_PARKING',    'TRANSPORT',      'EXPENSE', 'Parking', 6),
    ('TRANSPORT_TOLLS',      'TRANSPORT',      'EXPENSE', 'Tolls', 7),
    ('VEHICLE',              NULL,             'EXPENSE', 'Vehicle', 4),
    ('VEHICLE_SERVICE',      'VEHICLE',        'EXPENSE', 'Service', 1),
    ('VEHICLE_REPAIRS',      'VEHICLE',        'EXPENSE', 'Repairs', 2),
    ('VEHICLE_INSURANCE',    'VEHICLE',        'EXPENSE', 'Insurance', 3),
    ('SHOPPING',             NULL,             'EXPENSE', 'Shopping', 5),
    ('SHOPPING_CLOTHES',     'SHOPPING',       'EXPENSE', 'Clothes', 1),
    ('SHOPPING_ELECTRONICS', 'SHOPPING',       'EXPENSE', 'Electronics', 2),
    ('SHOPPING_ACCESSORIES', 'SHOPPING',       'EXPENSE', 'Accessories', 3),
    ('SHOPPING_ONLINE',      'SHOPPING',       'EXPENSE', 'Online Shopping', 4),
    ('ENTERTAINMENT',        NULL,             'EXPENSE', 'Entertainment', 6),
    ('ENTERTAINMENT_MOVIES', 'ENTERTAINMENT',  'EXPENSE', 'Movies', 1),
    ('ENTERTAINMENT_OTT',    'ENTERTAINMENT',  'EXPENSE', 'OTT', 2),
    ('ENTERTAINMENT_GAMES',  'ENTERTAINMENT',  'EXPENSE', 'Games', 3),
    ('ENTERTAINMENT_EVENTS', 'ENTERTAINMENT',  'EXPENSE', 'Events', 4),
    ('HEALTH',               NULL,             'EXPENSE', 'Healthcare', 7),
    ('HEALTH_MEDICINES',     'HEALTH',         'EXPENSE', 'Medicines', 1),
    ('HEALTH_DOCTOR',        'HEALTH',         'EXPENSE', 'Doctor Consultation', 2),
    ('HEALTH_TESTS',         'HEALTH',         'EXPENSE', 'Medical Tests', 3),
    ('UTILITIES',            NULL,             'EXPENSE', 'Utilities', 8),
    ('UTILITIES_ELECTRICITY','UTILITIES',      'EXPENSE', 'Electricity', 1),
    ('UTILITIES_WATER',      'UTILITIES',      'EXPENSE', 'Water', 2),
    ('UTILITIES_INTERNET',   'UTILITIES',      'EXPENSE', 'Internet', 3),
    ('UTILITIES_MOBILE',     'UTILITIES',      'EXPENSE', 'Mobile Recharge', 4),
    ('HOUSING',              NULL,             'EXPENSE', 'Housing', 9),
    ('HOUSING_RENT',         'HOUSING',        'EXPENSE', 'Rent', 1),
    ('HOUSING_MAINTENANCE',  'HOUSING',        'EXPENSE', 'Maintenance', 2),
    ('FINANCE',              NULL,             'EXPENSE', 'Financial Charges', 10),
    ('FINANCE_INTEREST',     'FINANCE',        'EXPENSE', 'Interest', 1),
    ('FINANCE_INSURANCE',    'FINANCE',        'EXPENSE', 'Insurance Premiums', 2),
    ('FINANCE_FEES',         'FINANCE',        'EXPENSE', 'Bank Fees & Charges', 3),
    ('PERSONAL_CARE',        NULL,             'EXPENSE', 'Personal Care', 11),
    ('EDUCATION',            NULL,             'EXPENSE', 'Education', 12),
    ('TRAVEL',               NULL,             'EXPENSE', 'Travel', 13),
    ('MISC',                 NULL,             'EXPENSE', 'Miscellaneous', 14),
    ('INC_SALARY',           NULL,             'INCOME',  'Salary', 1),
    ('INC_BONUS',            NULL,             'INCOME',  'Bonus & Incentives', 2),
    ('INC_FREELANCE',        NULL,             'INCOME',  'Freelance', 3),
    ('INC_BUSINESS',         NULL,             'INCOME',  'Business', 4),
    ('INC_RENTAL',           NULL,             'INCOME',  'Rental Income', 5),
    ('INC_INTEREST',         NULL,             'INCOME',  'Interest Income', 6),
    ('INC_GIFTS',            NULL,             'INCOME',  'Gifts', 7),
    ('INC_OTHER',            NULL,             'INCOME',  'Other Income', 8);

-- Seed defaults for users created before this migration.
INSERT INTO categories (user_id, system_code, type, name, sort_order)
SELECT u.id, t.code, t.type, t.name, t.sort_order
FROM users u CROSS JOIN category_templates t
WHERE t.parent_code IS NULL;

INSERT INTO categories (user_id, parent_id, system_code, type, name, sort_order)
SELECT p.user_id, p.id, t.code, t.type, t.name, t.sort_order
FROM category_templates t
JOIN categories p ON p.system_code = t.parent_code
WHERE t.parent_code IS NOT NULL;
