package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class EmptyCorrectionException extends BusinessException {

    public EmptyCorrectionException() {
        super(HttpStatus.BAD_REQUEST, "Informe o CNPJ, o total ou os dois para corrigir");
    }
}
