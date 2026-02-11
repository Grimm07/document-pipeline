from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_prefix": "ML_"}

    classifier_model: str = (
        "MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli"
    )
    ocr_model: str = "stepfun-ai/GOT-OCR-2.0-hf"
    candidate_labels: str = (
        "invoice,contract,report,letter,receipt,form,memo,resume,specification,manual"
    )
    device: str = "cuda"
    torch_dtype: str = "float16"
    ocr_max_pdf_pages: int = 10
    hf_home: str = "/models"

    def get_candidate_labels(self) -> list[str]:
        return [label.strip() for label in self.candidate_labels.split(",")]


settings = Settings()
