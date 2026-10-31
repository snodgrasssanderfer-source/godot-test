#!/usr/bin/env python3
"""用手机端同一量化 bge 模型生成评测余弦缓存。"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, processors

REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781"
BASE_URL = f"https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/{REVISION}/"
FILES = {
    "model_quantized.onnx": ("onnx/model_quantized.onnx", "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"),
    "vocab.txt": ("vocab.txt", "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"),
}
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
MAX_LENGTH = 256


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def model_files(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, (remote, checksum) in FILES.items():
        path = directory / name
        if not path.exists():
            with urllib.request.urlopen(BASE_URL + remote, timeout=120) as response:
                path.write_bytes(response.read())
        actual = sha256(path.read_bytes())
        if actual != checksum:
            raise ValueError(f"{path}: SHA256 {actual} != {checksum}")


def tokenizer_for(path):
    # 词表含 U+2028 字符；splitlines() 会把它当换行，错位全部后续词 ID。
    vocab = {word: index for index, word in enumerate(path.read_text().rstrip("\n").split("\n"))}
    tokenizer = Tokenizer(models.WordPiece(vocab=vocab, unk_token="[UNK]"))
    tokenizer.normalizer = normalizers.BertNormalizer(
        clean_text=True, handle_chinese_chars=True, strip_accents=False, lowercase=False
    )
    tokenizer.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    tokenizer.post_processor = processors.TemplateProcessing(
        single="[CLS] $A [SEP]",
        special_tokens=[("[CLS]", vocab["[CLS]"]), ("[SEP]", vocab["[SEP]"])],
    )
    tokenizer.enable_truncation(max_length=MAX_LENGTH)
    return tokenizer


def corpus_hash(memories, queries):
    # 只读取生产输入的 id/text；标准答案不参与向量生成。
    texts = {"memories": [[m["id"], m["text"]] for m in memories],
             "queries": [[q["id"], q["text"]] for q in queries]}
    return sha256(json.dumps(texts, ensure_ascii=False, separators=(",", ":")).encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--model-dir", type=Path, default=Path.home() / ".cache/xiaoke-memory-eval/bge-small-zh-v1.5")
    args = parser.parse_args()
    model_files(args.model_dir)
    tokenizer = tokenizer_for(args.model_dir / "vocab.txt")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.model_dir / "model_quantized.onnx"),
                                   sess_options=options, providers=["CPUExecutionProvider"])
    data = json.loads(args.corpus.read_text())
    memories = data["memories"]
    queries = data["queries"]
    timings = []

    def embed(text, query=False):
        started = time.perf_counter_ns()
        ids = np.array([tokenizer.encode((QUERY_PREFIX if query else "") + text).ids], dtype=np.int64)
        hidden = session.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids),
                                    "token_type_ids": np.zeros_like(ids)})[0][0, 0].copy()
        # Kotlin 对 float 乘积作 double 累加，再以 float 范数归一化。
        norm = np.float32(np.sqrt(np.sum(hidden * hidden, dtype=np.float64)))
        if norm > 0:
            hidden /= norm
        timings.append((time.perf_counter_ns() - started) / 1e6)
        return hidden

    documents = {memory["id"]: embed(memory["text"]) for memory in memories}
    scores = {}
    for query in queries:
        vector = embed(query["text"], query=True)
        scores[query["id"]] = {
            memory_id: float(np.sum(vector * document, dtype=np.float64))
            for memory_id, document in documents.items()
        }
    output = {
        "model": "bge-small-zh-v1.5", "revision": REVISION,
        "modelSha256": FILES["model_quantized.onnx"][1], "vocabSha256": FILES["vocab.txt"][1],
        "onnxruntime": ort.__version__, "tokenizers": __import__("tokenizers").__version__,
        "queryPrefix": QUERY_PREFIX, "maxLength": MAX_LENGTH, "pooling": "CLS", "normalization": "L2",
        "corpusSha256": corpus_hash(memories, queries), "scores": scores,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({"memories": len(memories), "queries": len(queries),
                      "pairs": len(memories) * len(queries),
                      "embedMsP50": float(np.percentile(timings, 50)),
                      "embedMsP95": float(np.percentile(timings, 95)),
                      "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
