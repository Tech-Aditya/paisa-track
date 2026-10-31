package com.paisatrack.modules.debt.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.debt.DebtDirection;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/borrowing")
@Tag(name = "Borrowing", description = "Money you received from others and must return. Not income")
class BorrowingController extends DebtController {

    BorrowingController(DebtService service, CurrentUser currentUser) {
        super(service, currentUser, DebtDirection.BORROWED);
    }
}
