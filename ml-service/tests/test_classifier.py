from unittest.mock import MagicMock

from app.services.classifier import ClassifierService, MAX_TEXT_CHARS


class TestClassifierService:
    def test_classify_returns_top_label_and_score(self, mock_classifier):
        label, score = mock_classifier.classify(
            "This is an invoice", ["invoice", "contract", "report"]
        )
        assert label == "invoice"
        assert score == 0.85

    def test_classify_empty_text_returns_unknown(self, mock_classifier):
        label, score = mock_classifier.classify("", ["invoice", "contract"])
        assert label == "unknown"
        assert score == 0.0
        mock_classifier._pipeline.assert_not_called()

    def test_classify_whitespace_only_returns_unknown(self, mock_classifier):
        label, score = mock_classifier.classify("   \n\t  ", ["invoice"])
        assert label == "unknown"
        assert score == 0.0

    def test_classify_trims_long_text(self, mock_classifier):
        long_text = "a" * (MAX_TEXT_CHARS + 1000)
        mock_classifier.classify(long_text, ["invoice"])
        called_text = mock_classifier._pipeline.call_args[0][0]
        assert len(called_text) == MAX_TEXT_CHARS

    def test_classify_passes_labels_and_multi_label_false(self, mock_classifier):
        labels = ["invoice", "contract"]
        mock_classifier.classify("some text", labels)
        _, kwargs = mock_classifier._pipeline.call_args
        assert kwargs["candidate_labels"] == labels
        assert kwargs["multi_label"] is False

    def test_classify_returns_float_score(self, mock_classifier):
        mock_classifier._pipeline.return_value = {
            "labels": ["report"],
            "scores": [0.999],
        }
        _, score = mock_classifier.classify("text", ["report"])
        assert isinstance(score, float)
