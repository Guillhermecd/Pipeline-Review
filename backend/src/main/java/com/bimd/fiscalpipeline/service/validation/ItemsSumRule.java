package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import com.bimd.fiscalpipeline.util.BrazilianFormats;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class ItemsSumRule implements DocumentRule {

    /** One cent: rounding each line and rounding the sum can differ by that much. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static final String MISSING_TOTAL = "total ausente";

    @Override
    public RuleCode code() {
        return RuleCode.ITEMS_SUM_EQUALS_TOTAL;
    }

    @Override
    public RuleResult validate(FiscalDataSnapshot data) {
        BigDecimal itemsSum = data.itemsSum() == null ? BigDecimal.ZERO : data.itemsSum();
        if (data.total() == null) {
            return new RuleResult(code(), false, BrazilianFormats.currency(itemsSum) + " vs " + MISSING_TOTAL);
        }
        boolean matches = itemsSum.subtract(data.total()).abs().compareTo(TOLERANCE) <= 0;
        String detail = BrazilianFormats.currency(itemsSum) + " vs " + BrazilianFormats.currency(data.total());
        return new RuleResult(code(), matches, detail);
    }
}
