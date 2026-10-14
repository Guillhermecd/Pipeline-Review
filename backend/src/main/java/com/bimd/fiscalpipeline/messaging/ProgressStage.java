package com.bimd.fiscalpipeline.messaging;

/** Stages the worker announces in {@code documento.progresso}. */
public enum ProgressStage {
    OCR,
    EXTRACTING,
    RETRY_WAIT
}
