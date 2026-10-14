package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import com.bimd.fiscalpipeline.util.CnpjCheckDigit;
import org.springframework.stereotype.Component;

@Component
public class CnpjCheckDigitRule implements DocumentRule {

    private static final int CHECK_DIGITS = 2;

    @Override
    public RuleCode code() {
        return RuleCode.CNPJ_CHECK_DIGIT;
    }

    @Override
    public RuleResult validate(FiscalDataSnapshot data) {
        String cnpj = CnpjCheckDigit.normalize(data.cnpj());
        if (cnpj.length() != CnpjCheckDigit.LENGTH) {
            return failed("%d caracteres, esperado %d".formatted(cnpj.length(), CnpjCheckDigit.LENGTH));
        }
        if (!CnpjCheckDigit.hasValidShape(cnpj)) {
            return failed("caracteres inválidos para um CNPJ");
        }
        String computed = CnpjCheckDigit.compute(cnpj);
        String read = cnpj.substring(CnpjCheckDigit.LENGTH - CHECK_DIGITS);
        if (!computed.equals(read)) {
            return failed("DV calculado %s, lido %s".formatted(computed, read));
        }
        return new RuleResult(code(), true, "DV %s confere".formatted(computed));
    }

    private RuleResult failed(String detail) {
        return new RuleResult(code(), false, detail);
    }
}
