# 파일: find_similar_full.py
import sys
import io
import requests
import pandas as pd
import numpy as np
import json
from sklearn.metrics.pairwise import cosine_similarity
from concurrent.futures import ThreadPoolExecutor, as_completed
import warnings
import os

# ==========================================
# 1️⃣ UTF-8 강제 출력 (한글 깨짐 방지)
# ==========================================
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# ==========================================
# 2️⃣ 경고 무시 (SSL / FutureWarning)
# ==========================================
warnings.filterwarnings("ignore", category=UserWarning)
warnings.filterwarnings("ignore", category=FutureWarning)

# ==========================================
# 3️⃣ 파라미터 처리 (기준종목 / 시작일 / 종료일)
# ==========================================
BASE_TICKER = sys.argv[1] if len(sys.argv) > 1 else "005930.KS"
START_DATE = sys.argv[2] if len(sys.argv) > 2 else "2024-03-07"
END_DATE = sys.argv[3] if len(sys.argv) > 3 else "2024-12-09"

# ==========================================
# 4️⃣ JSON 저장 경로
# ==========================================
RESULT_JSON_PATH = "python/data/similarity_result.json"
os.makedirs(os.path.dirname(RESULT_JSON_PATH), exist_ok=True)

# ==========================================
# 5️⃣ KRX 종목 리스트 가져오기
# ==========================================
def fetch_krx_list():
    url = "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13"
    resp = requests.get(url, verify=False)  # SSL 경고 무시
    df = pd.read_html(resp.text, header=0)[0]
    df = df[['종목코드', '회사명']]
    df['종목코드'] = df['종목코드'].astype(str).str.zfill(6) + ".KS"
    return df

# ==========================================
# 6️⃣ 기준 종목 / 타종목 데이터 생성 (임시 랜덤)
# ==========================================
def fetch_base_csv(ticker):
    dates = pd.date_range(start=START_DATE, end=END_DATE)
    df = pd.DataFrame({
        "Date": dates,
        "Close": np.random.rand(len(dates)) * 100
    })
    return df

def fetch_target_csv(ticker):
    dates = pd.date_range(start=START_DATE, end=END_DATE)
    df = pd.DataFrame({
        "Date": dates,
        "Close": np.random.rand(len(dates)) * 100
    })
    return df

# ==========================================
# 7️⃣ 유사도 계산
# ==========================================
def compute_similarity(base_df, target_df):
    merged = pd.merge(base_df, target_df, on="Date", how="inner", suffixes=("_base", "_target"))
    if merged.empty:
        return 0.0
    base_values = merged['Close_base'].values.reshape(1, -1)
    target_values = merged['Close_target'].values.reshape(1, -1)
    sim = cosine_similarity(base_values, target_values)[0][0]
    return float(sim)

# ==========================================
# 8️⃣ 병렬 분석 함수
# ==========================================
def analyze_target(row, base_df):
    target_ticker = row['종목코드']
    target_name = row['회사명']
    target_df = fetch_target_csv(target_ticker)
    sim = compute_similarity(base_df, target_df)
    return {
        "file": target_name,
        "ticker": target_ticker,
        "similarity": sim,
        "dates": base_df['Date'].astype(str).tolist(),
        "prices": target_df['Close'].tolist()
    }

# ==========================================
# 9️⃣ 메인 함수
# ==========================================
def main(base_ticker):
    print(f"[Python] 분석 시작: {base_ticker}, {START_DATE} ~ {END_DATE}")
    krx_list = fetch_krx_list()
    base_df = fetch_base_csv(base_ticker)

    results = []
    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = [executor.submit(analyze_target, row, base_df) for _, row in krx_list.iterrows()]
        for future in as_completed(futures):
            results.append(future.result())

    with open(RESULT_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=4)

    print(f"[Python] 결과 저장 완료: {RESULT_JSON_PATH}")

# ==========================================
# 10️⃣ 실행
# ==========================================
if __name__ == "__main__":
    main(BASE_TICKER)
