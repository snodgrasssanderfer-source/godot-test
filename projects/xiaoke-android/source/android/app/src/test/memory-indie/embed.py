# Cosine scores for the independent set: same ONNX model, CLS pooling and normalization as LocalEmbedder.
import json, sys
import numpy as np, onnxruntime as ort
model, tokens, out = sys.argv[1:4]
sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
t = json.load(open(tokens))
def vec(ids):
    a = np.array([ids], dtype=np.int64)
    h = sess.run(None, {"input_ids": a, "attention_mask": np.ones_like(a), "token_type_ids": np.zeros_like(a)})[0][0][0]
    return h / np.linalg.norm(h)
mem = {k: vec(v) for k, v in t["memories"].items()}
res = {q: {m: round(float(np.dot(vec(ids), mv)), 6) for m, mv in mem.items()} for q, ids in t["queries"].items()}
json.dump(res, open(out, "w"), separators=(",", ":"))
print("queries", len(res), "example", sorted(res["q001"].items(), key=lambda x: -x[1])[:3])
