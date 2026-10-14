package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import com.bimd.fiscalpipeline.util.BrazilianFormats;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IssueDateNotFutureRule implements DocumentRule {

    /** "Today" is the day in Brazil, not in UTC: a note issued at 22h must not look like tomorrow's. */
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    private static final String MISSING_DATE = "data ausente";

    private final Clock clock;

    @Override
    public RuleCode code() {
        return RuleCode.ISSUE_DATE_NOT_FUTURE;
    }

    @Override
    public RuleResult validate(FiscalDataSnapshot data) {
        if (data.issueDate() == null) {
            return new RuleResult(code(), false, MISSING_DATE);
        }
        LocalDate issueDate;
        try {
            issueDate = LocalDate.parse(data.issueDate());
        } catch (DateTimeParseException e) {
            return new RuleResult(code(), false, MISSING_DATE);
        }
        LocalDate today = LocalDate.now(clock.withZone(BRAZIL));
        return new RuleResult(code(), !issueDate.isAfter(today), BrazilianFormats.date(issueDate));
    }
}
