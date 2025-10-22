# find_similar_full.py
import platform
import sys
import subprocess
import logging
import os
import pandas as pd
import json
import FinanceDataReader as fdr
from sklearn.metrics.pairwise import cosine_similarity
import numpy as np
import matplotlib.pyplot as plt
from io import BytesIO
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

# ================= 환경 체크 =================
def check_environment():
    logger = logging.getLogger("env_check")
    logger.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stderr)
    formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    logger.info("=== 환경 체크 시작 ===")
    logger.info(f"OS: {platform.system()} {platform.version()}, Arch: {platform.architecture()[0]}")
    logger.info(f"Python Version: {sys.version}")
    for lib in ["pandas", "numpy", "FinanceDataReader", "matplotlib", "sklearn"]:
        try:
            __import__(lib)
        except ImportError:
            logger.warning(f"설치되지 않은 라이브러리: {lib}")
    logger.info("=== 환경 체크 완료 ===\n")

check_environment()

# ================= 경로 설정 =================
script_dir = os.path.dirname(os.path.abspath(__file__))
data_dir = os.path.join(script_dir, "stock_data")
os.makedirs(data_dir, exist_ok=True)

# ================= 데이터 다운로드/업데이트 =================
def fetch_fdr_and_save(symbol):
    file_path = os.path.join(data_dir, f"{symbol}.parquet")
    today = datetime.now().strftime('%Y-%m-%d')
    try:
        if os.path.exists(file_path):
            existing_df = pd.read_parquet(file_path)
            last_date = existing_df.index.max().strftime('%Y-%m-%d')
            new_data = fdr.DataReader(symbol, start=last_date, end=today)
            if not new_data.empty and new_data.index.max() > existing_df.index.max():
                updated_df = pd.concat([existing_df, new_data[new_data.index > existing_df.index.max()]])
                updated_df.to_parquet(file_path)
        else:
            df = fdr.DataReader(symbol)
            df.to_parquet(file_path)
    except Exception as e:
        print(json.dumps({"error": f"{symbol} 데이터 처리 실패: {str(e)}"}))
        return

def save_all_data():
    try:
        krx_list = fdr.StockListing('KRX')
        symbols = krx_list['Symbol'].tolist()
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = {executor.submit(fetch_fdr_and_save, s): s for s in symbols}
            for future in as_completed(futures):
                future.result()
    except Exception as e:
        print(json.dumps({"error": f"KRX 종목 리스트 로드 실패: {str(e)}"}))
        return

# ================= 차트 생성 =================
def plot_and_get_base64(chart_data_list, start_date, end_date, title):
    plt.rc('font', family='Malgun Gothic')
    plt.rcParams['axes.unicode_minus'] = False
    plt.figure(figsize=(15, 8))
    for item in chart_data_list:
        plt.plot(item['data'].index, item['data']['Close'], label=item['label'], linewidth=item.get('linewidth', 1.5))
    plt.title(f"[{title}] 차트 ({start_date} ~ {end_date})")
    plt.xlabel("날짜")
    plt.ylabel("종가")
    plt.legend()
    plt.grid(True)
    buffer = BytesIO()
    plt.savefig(buffer, format='png')
    buffer.seek(0)
    base64_image = base64.b64encode(buffer.read()).decode('utf-8')
    plt.close()
    return base64_image

# ================= 유사 종목 조회 =================
def find_similar_chart(base_symbol, start_date, end_date, n_similar_stocks=5):
    save_all_data()
    base_file = os.path.join(data_dir, f"{base_symbol}.parquet")
    if not os.path.exists(base_file):
        print(json.dumps({"error": f"기준 종목 데이터 없음: {base_symbol}"}))
        return

    base_data = pd.read_parquet(base_file).loc[start_date:end_date]
    if base_data.empty:
        print(json.dumps({"error": f"기준 종목 ({base_symbol}) 데이터 기간 없음"}))
        return

    base_close_norm = (base_data['Close'] - base_data['Close'].mean()) / base_data['Close'].std()
    similarities = []

    krx_list = fdr.StockListing('KRX')
    krx_name_map = krx_list.set_index('Symbol')['Name'].to_dict()

    for symbol in krx_list['Symbol']:
        if symbol == base_symbol:
            continue
        file_path = os.path.join(data_dir, f"{symbol}.parquet")
        if not os.path.exists(file_path):
            continue
        data = pd.read_parquet(file_path).loc[start_date:end_date]
        data_norm = (data['Close'] - data['Close'].mean()) / data['Close'].std()
        data_norm = data_norm.reindex(base_close_norm.index).interpolate(method='linear')
        cos_sim = cosine_similarity(base_close_norm.values.reshape(1, -1), data_norm.values.reshape(1, -1)).item()
        similarities.append({"ticker": symbol, "name": krx_name_map.get(symbol, "알 수 없음"), "cosine_similarity": cos_sim})

    top_similar = sorted(similarities, key=lambda x: x['cosine_similarity'], reverse=True)[:n_similar_stocks]
    print(json.dumps({"base_symbol": base_symbol, "similar_stocks": top_similar}, ensure_ascii=False))

# ================= 개별 차트 조회 =================
def plot_single_chart(base_symbol, compare_symbol, start_date, end_date):
    chart_data_list = []
    base_file = os.path.join(data_dir, f"{base_symbol}.parquet")
    compare_file = os.path.join(data_dir, f"{compare_symbol}.parquet")
    if not os.path.exists(base_file) or not os.path.exists(compare_file):
        print(json.dumps({"error": "기준 또는 비교 종목 데이터 없음"}))
        return

    base_data = pd.read_parquet(base_file).loc[start_date:end_date]
    compare_data = pd.read_parquet(compare_file).loc[start_date:end_date]
    chart_data_list.append({'data': base_data, 'label': f"{base_symbol} (기준)", 'linewidth': 2.5})
    chart_data_list.append({'data': compare_data, 'label': f"{compare_symbol} (비교)", 'linewidth': 1.5})

    base64_image = plot_and_get_base64(chart_data_list, start_date, end_date, f"{base_symbol} vs {compare_symbol}")
    print(json.dumps({"image_data": base64_image}))

# ================= 실행 =================
if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="주가 차트 유사성 분석 및 시각화")
    parser.add_argument("--base_symbol", type=str, required=True, help="기준 종목 코드")
    parser.add_argument("--start_date", type=str, default="2023-01-01", help="시작일")
    parser.add_argument("--end_date", type=str, default=datetime.now().strftime('%Y-%m-%d'), help="종료일")
    parser.add_argument("--compare_symbol", type=str, help="개별 비교 종목 코드")
    parser.add_argument("--n_similar", type=int, default=5, help="유사 종목 수")
    args = parser.parse_args()

    if args.compare_symbol:
        plot_single_chart(args.base_symbol, args.compare_symbol, args.start_date, args.end_date)
    else:
        find_similar_chart(args.base_symbol, args.start_date, args.end_date, args.n_similar)
