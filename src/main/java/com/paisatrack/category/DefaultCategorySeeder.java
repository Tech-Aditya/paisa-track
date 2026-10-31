package com.paisatrack.category;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.paisatrack.user.UserRegisteredEvent;

/** Copies {@code category_templates} into a new user's own, editable categories. */
@Component
class DefaultCategorySeeder {

    private final JdbcTemplate jdbc;

    DefaultCategorySeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void onUserRegistered(UserRegisteredEvent event) {
        jdbc.update("""
                insert into categories (user_id, system_code, type, name, sort_order)
                select ?, t.code, t.type, t.name, t.sort_order
                from category_templates t where t.parent_code is null""", event.userId());
        jdbc.update("""
                insert into categories (user_id, parent_id, system_code, type, name, sort_order)
                select p.user_id, p.id, t.code, t.type, t.name, t.sort_order
                from category_templates t
                join categories p on p.user_id = ? and p.system_code = t.parent_code
                where t.parent_code is not null""", event.userId());
    }
}
