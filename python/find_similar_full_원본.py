import platform
import sys
import subprocess
import logging

# === 환경 체크 ===
def check_environment():
    logger = logging.getLogger("env_check")
    logger.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stderr)
    formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    handler.setFormatter(formatter)
    logger.addHandler(handler)

    logger.info("=== 환경 체크 시작 ===")

    # OS 정보
    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()[0]
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")

    # Python 버전
    py_ver = sys.version
    logger.info(f"Python Version: {py_ver}")

    # 필수 라이브러리 체크
    missing_libs = []
    for lib in ["pandas", "numpy", "FinanceDataReader", "matplotlib", "sklearn"]:
        try:
            __import__(lib)
        except ImportError:
            missing_libs.append(lib)
    if missing_libs:
        logger.warning(f"설치되지 않은 라이브러리: {', '.join(missing_libs)}")
    else:
        logger.info("필수 라이브러리 모두 설치됨")

    # Windows에서 Visual C++ Redistributable 체크
    if os_name == "Windows":
        try:
            result = subprocess.run(
                ["reg", "query", r"HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64"],
                capture_output=True, text=True
            )
            if "Installed" in result.stdout:
                logger.info("Visual C++ Redistributable 설치됨")
            else:
                logger.warning("Visual C++ Redistributable 미설치")
        except Exception as e:
            logger.warning(f"C++ Redistributable 체크 실패: {e}")

    logger.info("=== 환경 체크 완료 ===\n")

# === 기존 스크립트 시작 전에 환경 체크 호출 ===
check_environment()

# ==========================================================================

import pandas as pd
import json
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
import FinanceDataReader as fdr
from sklearn.metrics.pairwise import cosine_similarity
import numpy as np
import argparse
import time
import os
from datetime import datetime, timedelta
import threading
import sys
import backoff
from http.client import RemoteDisconnected
import matplotlib.pyplot as plt
import base64
from io import BytesIO
import urllib3

# SSL 경고 비활성화
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# 스크립트 파일의 디렉토리 경로를 기준으로 경로 설정
script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "data", "my_log_file.log")
data_dir = os.path.join(script_dir, "stock_data")

# 로그 파일 및 데이터 디렉토리 생성 (없으면)
os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)

# 로깅 설정: 표준 에러(stderr)에 로그 출력
logger = logging.getLogger()
logger.setLevel(logging.INFO)  # 원래의 INFO 레벨 유지
formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
file_handler = logging.FileHandler(log_file_path, encoding='utf-8')
file_handler.setFormatter(formatter)
logger.addHandler(file_handler)
stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)

@backoff.on_exception(backoff.expo, RemoteDisconnected, max_tries=5, jitter=backoff.full_jitter)
def fetch_fdr_with_retry(symbol, start=None, end=None):
    """
    재시도 로직을 포함하여 FinanceDataReader 호출을 래핑하는 함수
    """
    return fdr.DataReader(symbol, start=start, end=end)

def fetch_fdr_and_save(symbol):
    """
    FinanceDataReader를 사용하여 주가 데이터를 가져와 파일로 저장/업데이트하는 함수
    """
    file_path = os.path.join(data_dir, f"{symbol}.parquet")
    today = datetime.now().strftime('%Y-%m-%d')
    try:
        if os.path.exists(file_path):
            existing_df = pd.read_parquet(file_path)
            last_date = existing_df.index.max().strftime('%Y-%m-%d')
            new_data = fetch_fdr_with_retry(symbol, start=last_date, end=today)
            if not new_data.empty and new_data.index.max() > existing_df.index.max():
                updated_df = pd.concat([existing_df, new_data[new_data.index > existing_df.index.max()]])
                updated_df.to_parquet(file_path)
                logger.info(f"{symbol} 데이터 업데이트 완료")
        else:
            df = fetch_fdr_with_retry(symbol)
            df.to_parquet(file_path)
            logger.info(f"{symbol} 전체 데이터 새로 저장")
    except Exception as e:
        logger.error(f"{symbol} 데이터 처리 실패: {e}")

def save_all_data():
    """
    오늘 종가 데이터가 있는 경우에만 모든 종목의 데이터를 업데이트하는 함수
    """
    logger.info("오늘 종가 데이터 확인 중...")
    today = datetime.now().strftime('%Y-%m-%d')

    try:
        sample_data = fdr.DataReader('005930', start=today, end=today)
        if sample_data.empty or sample_data.index.max() < datetime.now().date():
            logger.info("오늘 종가 데이터가 없어 업데이트를 건너뜁니다.")
            return
    except Exception as e:
        logger.warning(f"오늘 종가 데이터 확인 중 오류 발생: {e}. 업데이트를 건너뜁니다.")
        return

    logger.info("오늘 종가 데이터가 확인되었습니다. 모든 종목 데이터 업데이트 시작...")
    try:
        krx_list = fdr.StockListing('KRX')
        symbol_col = next((c for c in ['Symbol', 'Code'] if c in krx_list.columns.tolist()), None)
        if not symbol_col:
            raise KeyError("KRX 리스트에서 'Symbol' 또는 'Code' 컬럼을 찾을 수 없습니다.")
        krx_symbols = krx_list[symbol_col].tolist()
    except Exception as e:
        logger.error(f"KRX 종목 리스트 로드 실패: {e}")
        return

    with ThreadPoolExecutor(max_workers=5) as executor: # Worker 수를 적당히 조절
        futures = {executor.submit(fetch_fdr_and_save, symbol): symbol for symbol in krx_symbols}
        for future in as_completed(futures):
            future.result()
    logger.info("모든 종목 데이터 업데이트 완료.")

def plot_and_get_base64(chart_data_list, start_date, end_date, title):
    """
    차트 데이터를 받아 차트를 그리고 Base64 문자열로 반환하는 헬퍼 함수
    """
    # matplotlib 한글 폰트 설정
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

def find_similar_chart(base_symbol, start_date, end_date, n_similar_stocks=5):
    save_all_data()

    logger.info(f"분석 시작 - 기준 종목: {base_symbol}, 시작일: {start_date}, 종료일: {end_date}")

    try:
        krx_list = fdr.StockListing('KRX')
        available_cols = krx_list.columns.tolist()
        symbol_col = next((c for c in ['Symbol', 'Code'] if c in available_cols), None)
        name_col = next((c for c in ['Name'] if c in available_cols), None)
        if not symbol_col or not name_col:
            raise KeyError(f"필요한 컬럼 중 하나가 없습니다. (현재 컬럼: {available_cols})")
        krx_symbols = krx_list[symbol_col].tolist()
        krx_name_map = krx_list.set_index(symbol_col)[name_col].to_dict()
    except Exception as e:
        logger.error(f"KRX 종목 리스트 로드 실패: {e}")
        return None

    try:
        base_data = pd.read_parquet(os.path.join(data_dir, f"{base_symbol}.parquet"))
        base_data = base_data.loc[start_date:end_date]
    except FileNotFoundError:
        logger.error(f"기준 종목({base_symbol})의 데이터 파일이 존재하지 않습니다.")
        return None

    if base_data.empty:
        logger.error(f"기준 종목 ({base_symbol})의 데이터 기간이 너무 짧거나 데이터가 없습니다.")
        return None

    base_close_prices = base_data['Close']
    similarities = []

    def process_stock(symbol):
        if symbol == base_symbol:
            return None
        try:
            file_path = os.path.join(data_dir, f"{symbol}.parquet")
            if not os.path.exists(file_path):
                return None
            data = pd.read_parquet(file_path)
            close_prices = data['Close'].loc[start_date:end_date]
            close_prices = close_prices.reindex(base_close_prices.index).interpolate(method='linear')
            if close_prices.isnull().any():
                logger.warning(f"데이터 불충분: {symbol}")
                return None
            # 정규화: 평균을 빼고 표준편차로 나누기
            base_prices_norm = (base_close_prices - np.mean(base_close_prices)) / np.std(base_close_prices)
            stock_prices_norm = (close_prices - np.mean(close_prices)) / np.std(close_prices)
            cos_sim = cosine_similarity(base_prices_norm.values.reshape(1, -1), stock_prices_norm.values.reshape(1, -1))
            return {
                "ticker": symbol,
                "name": krx_name_map.get(symbol, '알 수 없음'),
                "cosine_similarity": cos_sim.item()
            }
        except Exception as e:
            logger.error(f"데이터 처리 중 오류 발생 {symbol}: {e}")
            return None

    with ThreadPoolExecutor(max_workers=5) as executor: # Worker 수를 적당히 조절
        futures = {executor.submit(process_stock, symbol): symbol for symbol in krx_symbols}
        for future in as_completed(futures):
            result = future.result()
            if result:
                similarities.append(result)

    similarities.sort(key=lambda x: x['cosine_similarity'], reverse=True)
    top_n_similar_stocks = similarities[:n_similar_stocks]

    logger.info(f"분석 완료 - 상위 {len(top_n_similar_stocks)}개 종목 결과")

    return top_n_similar_stocks

def plot_single_chart(base_symbol, compare_symbol, start_date, end_date):
    """
    기준 종목과 개별 비교 종목의 차트를 그리고 Base64 문자열로 반환하는 함수
    """
    logger.info(f"개별 차트 그리기 시작: 기준 종목={base_symbol}, 비교 종목={compare_symbol}")

    chart_data_list = []

    try:
        base_data = pd.read_parquet(os.path.join(data_dir, f"{base_symbol}.parquet"))
        base_data = base_data.loc[start_date:end_date]
        chart_data_list.append({'data': base_data, 'label': f"{base_symbol} (기준)", 'linewidth': 2.5})
    except FileNotFoundError:
        logger.error(f"차트용 데이터 로드 실패: {base_symbol}")
        return None

    try:
        compare_data = pd.read_parquet(os.path.join(data_dir, f"{compare_symbol}.parquet"))
        compare_data = compare_data.loc[start_date:end_date]
        chart_data_list.append({'data': compare_data, 'label': f"{compare_symbol} (비교)", 'linewidth': 1.5})
    except FileNotFoundError:
        logger.error(f"차트용 데이터 로드 실패: {compare_symbol}")
        return None

    if not chart_data_list or any(item['data'].empty for item in chart_data_list):
        logger.error("차트 데이터를 생성할 수 없습니다. 데이터 기간을 확인하세요.")
        return None

    title = f"{base_symbol} vs {compare_symbol}"
    return plot_and_get_base64(chart_data_list, start_date, end_date, title)

def main():
    parser = argparse.ArgumentParser(description="주가 차트 유사성 분석 및 시각화")
    parser.add_argument("--base_symbol", type=str, required=True, help="비교 기준이 될 종목 코드")
    parser.add_argument("--start_date", type=str, default="2023-01-01", help="분석 시작일 (YYYY-MM-DD)")
    parser.add_argument("--end_date", type=str, default=datetime.now().strftime('%Y-%m-%d'), help="분석 종료일 (YYYY-MM-DD)")
    parser.add_argument("--compare_symbol", type=str, help="개별 비교할 종목 코드 (find_similar_chart와 함께 사용할 수 없음)")
    parser.add_argument("--n_similar", type=int, default=5, help="유사한 차트 패턴을 찾을 종목 수 (plot_single_chart와 함께 사용할 수 없음)")
    args = parser.parse_args()

    if args.compare_symbol and args.n_similar != 5:
        logger.error("Error: --compare_symbol와 --n_similar 옵션은 함께 사용할 수 없습니다.")
        return

    try:
        if args.compare_symbol:
            base64_image = plot_single_chart(args.base_symbol, args.compare_symbol, args.start_date, args.end_date)
            if base64_image:
                json_result = json.dumps({"image_data": base64_image})
                print(json_result)
            else:
                print(json.dumps({"error": "차트 이미지를 생성할 수 없습니다."}, ensure_ascii=False))
        else:
            similar_stocks = find_similar_chart(args.base_symbol, args.start_date, args.end_date, args.n_similar)
            if similar_stocks:
                result = {"base_symbol": args.base_symbol, "similar_stocks": similar_stocks}
                json_result = json.dumps(result, ensure_ascii=False, indent=2)
                print(json_result)
            else:
                print(json.dumps({"error": "유사한 종목을 찾을 수 없습니다."}, ensure_ascii=False))
    except Exception as e:
        logger.error(f"스크립트 실행 중 치명적 오류 발생: {e}")
        print(json.dumps({"error": f"스크립트 실행 중 오류 발생: {str(e)}"}, ensure_ascii=False))

if __name__ == "__main__":
    main()