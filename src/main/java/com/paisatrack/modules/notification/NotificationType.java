package com.paisatrack.modules.notification;

/** What a reminder is about. Each type has its own preference (on/off, days before). */
public enum NotificationType {
    EMI("#/loans"),
    BILL("#/recurring"),
    SUBSCRIPTION("#/recurring"),
    RECURRING("#/recurring"),
    LENDING("#/debts"),
    BORROWING("#/debts"),
    BUDGET("#/overview"),
    SALARY("#/overview");

    private final String link;

    NotificationType(String link) {
        this.link = link;
    }

    /** Web app route that shows the item. */
    String link() {
        return link;
    }
}
