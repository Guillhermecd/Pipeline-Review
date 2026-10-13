from fiscal_worker.domain.events import DocumentReceivedPayload, Stage
from fiscal_worker.domain.extraction import DocumentText, FieldSource, TextLine
from fiscal_worker.helpers.cpf import MASK
from fiscal_worker.services.extraction_service import ExtractionService
from support import FakeFileStore, FakeLlm, FakeQrReader, FakeTextReader, truth

RECEIVED = DocumentReceivedPayload(
    file_key="documents/abc/recibo.jpg", mime_type="image/jpeg", file_name="recibo.jpg"
)
TEXT = DocumentText(
    engine="paddleocr",
    lines=(
        TextLine("RECIBO", 0.99),
        TextLine("Auto Posto Via Norte", 0.97),
        TextLine("CNPJ: 04.543.218/0001-43", 0.96),
        TextLine("Consumidor CPF: 123.456.789-09", 0.95),
        TextLine("Chave 35261004543218000143650010000012341123456780", 0.95),
        TextLine("07/10/2026", 0.94),
        TextLine("Gasolina comum (L)  38,2  6,19", 0.93),
        TextLine("TOTAL R$ 236,46", 0.92),
    ),
)


def service(llm: FakeLlm, qr_cnpj: str | None = None) -> ExtractionService:
    return ExtractionService(
        files=FakeFileStore(files={RECEIVED.file_key: b"jpeg bytes"}),
        text_reader=FakeTextReader(TEXT),
        qr_reader=FakeQrReader(qr_cnpj),
        llm=llm,
    )


async def ignore(stage: Stage) -> None: ...


def sample_truth() -> FakeLlm:
    from sample_data import SAMPLES_BY_KEY

    return FakeLlm(extraction=truth(SAMPLES_BY_KEY["s2"]))


async def test_individual_cpf_never_reaches_the_llm_but_company_data_does() -> None:
    llm = sample_truth()

    await service(llm).extract(RECEIVED, ignore)

    sent = llm.received_text[0]
    assert "123.456.789-09" not in sent
    assert MASK in sent
    assert "04.543.218/0001-43" in sent
    assert "35261004543218000143650010000012341123456780" in sent


async def test_announces_each_stage_before_doing_its_work() -> None:
    llm = sample_truth()
    timeline: list[str] = []

    async def record(stage: Stage) -> None:
        timeline.append(f"{stage.value}:llm_calls={len(llm.received_text)}")

    await service(llm).extract(RECEIVED, record)

    assert timeline == ["OCR:llm_calls=0", "EXTRACTING:llm_calls=0"]


async def test_scores_fields_against_the_ocr_text() -> None:
    payload = await service(sample_truth()).extract(RECEIVED, ignore)

    # The access key line embeds the CNPJ too, so both lines count: mean of 0.96 and 0.95.
    assert payload.fields.cnpj.confidence == 0.955
    assert payload.fields.cnpj.source is FieldSource.OCR
    assert payload.fields.total.confidence == 0.92
    assert payload.fields.issue_date.confidence == 0.94
    assert payload.items_confidence == 0.93
    assert payload.items[0].quantity == "38.2"


async def test_qr_code_cnpj_wins_over_the_printed_one() -> None:
    payload = await service(sample_truth(), qr_cnpj="27.365.041/0001-39").extract(RECEIVED, ignore)

    assert payload.fields.cnpj.value == "27.365.041/0001-39"
    assert payload.fields.cnpj.source is FieldSource.QR_CODE
