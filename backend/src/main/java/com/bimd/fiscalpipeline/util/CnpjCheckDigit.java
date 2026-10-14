package com.bimd.fiscalpipeline.util;

import java.util.Locale;

/**
 * CNPJ check digits by modulo 11. Works for the alphanumeric CNPJ too: each character is worth
 * its ASCII code minus 48, so digits keep their value and {@code A} is 17.
 */
public final class CnpjCheckDigit {

    public static final int LENGTH = 14;

    private static final int BASE_LENGTH = 12;
    private static final int[] FIRST_WEIGHTS = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] SECOND_WEIGHTS = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int MODULUS = 11;
    private static final int ASCII_OFFSET = 48;

    private CnpjCheckDigit() {
    }

    /** Drops the punctuation ({@code . / -}) and spaces, and upper-cases the letters. */
    public static String normalize(String cnpj) {
        return cnpj == null ? "" : cnpj.replaceAll("[./\\-\\s]", "").toUpperCase(Locale.ROOT);
    }

    /** True when the text is 12 letters or digits followed by 2 digits. */
    public static boolean hasValidShape(String normalized) {
        return normalized.matches("[0-9A-Z]{12}[0-9]{2}");
    }

    /**
     * The two check digits for the first 12 characters, as text ({@code "81"}).
     *
     * @param normalized at least 12 characters of {@code [0-9A-Z]}
     */
    public static String compute(String normalized) {
        int[] values = new int[BASE_LENGTH + 1];
        for (int i = 0; i < BASE_LENGTH; i++) {
            values[i] = normalized.charAt(i) - ASCII_OFFSET;
        }
        int first = digit(values, FIRST_WEIGHTS);
        values[BASE_LENGTH] = first;
        return "" + first + digit(values, SECOND_WEIGHTS);
    }

    private static int digit(int[] values, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += values[i] * weights[i];
        }
        int remainder = sum % MODULUS;
        return remainder < 2 ? 0 : MODULUS - remainder;
    }
}
