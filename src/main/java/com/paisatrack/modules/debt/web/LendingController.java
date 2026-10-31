package com.paisatrack.modules.debt.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.debt.DebtDirection;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/lending")
@Tag(name = "Lending", description = "Money you gave others and expect back. Not an expense")
class LendingController extends DebtController {

    LendingController(DebtService service, CurrentUser currentUser) {
        super(service, currentUser, DebtDirection.LENT);
    }
}
