package com.bimd.fiscalpipeline.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Texts the screens show as they come from the API, in the Brazilian format. */
public final class BrazilianFormats {

    private static final Locale BRAZIL = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private BrazilianFormats() {
    }

    /** {@code R$ 1.234,56} */
    public static String currency(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(BRAZIL));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return "R$ " + format.format(value);
    }

    /** {@code 28/09/2026} */
    public static String date(LocalDate date) {
        return date.format(DATE);
    }

    /** {@code 0.78 -> 78%}, rounded half up as the frontend does. */
    public static String percent(BigDecimal ratio) {
        return ratio.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
