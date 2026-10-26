#!/usr/bin/env python3
"""연관 상품 이웃 파일 생성기(DESIGN.md 6.8.1절, ADR 0014, Jira 511).

풀 → e5-large 브랜드 없는 텍스트 임베딩 → 전체 쌍 코사인 상위 k → 이웃 파일. 자바 `RelatedNeighbors` 가 읽는다.

    # 임베딩부터(GPU 가상환경, DESIGN.md 25절. CPU 로도 돌지만 전량에 몇 시간 걸린다)
    C:/Users/SSAFY/.venvs/related-gpu/Scripts/python.exe tools/build_related.py --embed --cuda

    # 이미 만든 임베딩을 다시 쓸 때(실험 캐시 docs/related-cache/e5nb.npy — 같은 모델·같은 텍스트·같은 접두어)
    python tools/build_related.py --embeddings docs/related-cache/e5nb.npy

텍스트는 제목에서 대괄호 라벨([브랜드]·[선물세트])을 뺀 것 + 카테고리이고, e5 의 대칭 유사도 접두어 "query: " 를 붙인다.
브랜드를 빼는 이유는 ADR 0014 — 브랜드가 든 임베딩은 같은 브랜드 다른 종류로 쏠렸다.

파일 형식(빅엔디언, gzip 헤더 시각 0 — 같은 입력이면 같은 바이트).

    마법 "CPHREL01"(8) · 풀 파일 sha256 16진(64, ASCII) · 문서 수 n(int32) · k(int32) · n × k int32(이웃의 풀 순번, 모자라면 -1)

이웃은 코사인 내림차순이고 같으면 풀 순번 오름차순이다. 자기 자신은 뺀다. 풀이 바뀌면 이 도구를 다시 돌리고 파일을 커밋한다.
"""

import argparse
import gzip
import hashlib
import json
import os
import re
import struct
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent
POOL = MODULE / "src/main/resources/catalog/pool.json.gz"
OUT = MODULE / "src/main/resources/catalog/related-e5nb.bin.gz"
MODEL = "intfloat/multilingual-e5-large"
PREFIX = "query: "
MAGIC = b"CPHREL01"
BRACKET = re.compile(r"\[[^\]]*\]")


def texts(products):
    return [PREFIX + " ".join(x for x in (BRACKET.sub(" ", p["title"]).strip(), p.get("category") or "") if x)
            for p in products]


def embed(docs, cuda, batch):
    # 윈도우에서 huggingface_hub 가 심볼릭 링크를 만들다 WinError 1314 로 죽는다
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS", "1")
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    if cuda:
        import onnxruntime
        onnxruntime.preload_dlls()
    from fastembed import TextEmbedding
    model = TextEmbedding(MODEL, providers=["CUDAExecutionProvider"]) if cuda else TextEmbedding(MODEL)
    if cuda and model.model.model.get_providers()[0] != "CUDAExecutionProvider":
        sys.exit("CUDA 실행기가 안 잡혔다. onnxruntime-gpu 와 CUDA 13 라이브러리를 확인한다(DESIGN.md 25절)")
    vectors = np.asarray(list(model.embed(docs, batch_size=batch)), dtype=np.float32)
    vectors /= np.linalg.norm(vectors, axis=1, keepdims=True) + 1e-12
    return vectors


def neighbors(vectors, k, block=1000):
    n = len(vectors)
    out = np.full((n, k), -1, dtype=np.int32)
    for start in range(0, n, block):
        sim = vectors[start:start + block] @ vectors.T
        rows = np.arange(sim.shape[0])
        sim[rows, start + rows] = -np.inf
        # 상위 k 를 넉넉히 고른 뒤 (코사인 내림차순, 순번 오름차순)으로 정렬한다. argpartition 만으로는 동점 순서가 흔들린다
        take = min(n - 1, k + 8)
        part = np.argpartition(-sim, take, axis=1)[:, :take]
        for r in range(sim.shape[0]):
            cand = part[r]
            scores = sim[r, cand]
            order = np.lexsort((cand, -scores))[:k]
            out[start + r, :len(order)] = cand[order]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", default=str(POOL))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--embeddings", help="이미 만든 임베딩 npy(풀 순서, L2 정규화). 주면 임베딩을 건너뛴다")
    ap.add_argument("--embed", action="store_true", help="풀에서 임베딩을 새로 만든다")
    ap.add_argument("--cuda", action="store_true")
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--k", type=int, default=20)
    args = ap.parse_args()

    raw = Path(args.pool).read_bytes()
    pool_sha = hashlib.sha256(raw).hexdigest()
    products = json.loads(gzip.decompress(raw))["products"]
    t0 = time.perf_counter()
    if args.embeddings:
        vectors = np.load(args.embeddings).astype(np.float32)
        if len(vectors) != len(products):
            sys.exit(f"임베딩 {len(vectors)}건과 풀 {len(products)}건이 다르다")
    elif args.embed:
        vectors = embed(texts(products), args.cuda, args.batch)
    else:
        sys.exit("--embeddings 또는 --embed 중 하나")
    t1 = time.perf_counter()
    table = neighbors(vectors, args.k)
    t2 = time.perf_counter()

    body = MAGIC + pool_sha.encode("ascii") + struct.pack(">ii", len(products), args.k) + table.astype(">i4").tobytes()
    with open(args.out, "wb") as f:
        with gzip.GzipFile(filename="", mode="wb", fileobj=f, mtime=0) as g:
            g.write(body)
    size = Path(args.out).stat().st_size
    print(json.dumps({"pool": args.pool, "poolSha256": pool_sha, "count": len(products), "k": args.k,
                      "embedSeconds": round(t1 - t0, 1), "neighborSeconds": round(t2 - t1, 1),
                      "out": args.out, "bytes": size,
                      "sha256": hashlib.sha256(Path(args.out).read_bytes()).hexdigest()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
