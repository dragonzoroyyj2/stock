import platform
import sys
import subprocess
import logging
import pandas as pd
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
import FinanceDataReader as fdr
from sklearn.metrics.pairwise import cosine_similarity
import numpy as np
import argparse
import time
import os
from datetime import datetime
import threading
import backoff
from http.client import RemoteDisconnected
import matplotlib.pyplot as plt
import base64
from io import BytesIO
import ssl
import urllib3

# ========================================================================
# 환경 체크 함수: OS, Python 버전, 필수 라이브러리, Windows C++ Redistributable
# ========================================================================
def check_environment():
    logger = logging.getLogger("env_check")
    logger.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stderr)
    formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    handler.setFormatter(formatter)
    logger.addHandler(handler)

    logger.info("=== 환경 체크 시작 ===")

    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()[0]
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")

    py_ver = sys.version
    logger.info(f"Python Version: {py_ver}")

    missing_libs = []
    for lib in ["pandas", "numpy", "FinanceDataReader", "matplotlib", "sklearn", "backoff"]:
        try:
            __import__(lib)
        except ImportError:
            missing_libs.append(lib)
    if missing_libs:
        logger.warning(f"설치되지 않은 라이브러리: {', '.join(missing_libs)}")
    else:
        logger.info("필수 라이브러리 모두 설치됨")

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

# 스크립트 실행 시 바로 환경 체크
check_environment()

# ========================================================================
# 디렉토리 및 로깅 설정
# ========================================================================
script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "log", "my_log_file.log")
data_dir = os.path.join(script_dir, "stock_data")
os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)

logger = logging.getLogger()
logger.setLevel(logging.INFO)
formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
file_handler = logging.FileHandler(log_file_path, encoding='utf-8')
file_handler.setFormatter(formatter)
logger.addHandler(file_handler)
stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)

# ========================================================================
# FinanceDataReader 재시도 래퍼 (네트워크/SSL 오류 시 즉시 중단)
# ========================================================================
@backoff.on_exception(backoff.expo, (RemoteDisconnected, ssl.SSLError, urllib3.exceptions.SSLError), max_tries=3, jitter=backoff.full_jitter)
def fetch_fdr_with_retry(symbol, start=None, end=None):
    """
    FinanceDataReader 호출을 재시도하면서 네트워크 오류나 SSL 오류 시 예외 발생
    """
    try:
        return fdr.DataReader(symbol, start=start, end=end)
    except Exception as e:
        logger.warning(f"{symbol} 데이터 업데이트 실패: {e}")
        # 치명적 오류이면 바로 종료
        raise RuntimeError(f"{symbol} 데이터 조회 중 치명적 오류 발생: {e}") from e

# ========================================================================
# 종목별 데이터 저장/업데이트
# ========================================================================
def fetch_fdr_and_save(symbol):
    """
    개별 종목 데이터를 가져와 parquet 파일로 저장/업데이트
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
        raise RuntimeError(f"{symbol} 데이터 처리 중 오류 발생: {e}") from e

# ========================================================================
# 모든 종목 데이터 업데이트 (오늘 종가가 없으면 건너뜀)
# ========================================================================
def save_all_data():
    """
    오늘 종가가 확인되면 모든 종목 데이터 업데이트
    """
    logger.info("오늘 종가 데이터 확인 중...")
    today = datetime.now().strftime('%Y-%m-%d')
    try:
        sample_data = fdr.DataReader('005930', start=today, end=today)
        if sample_data.empty or sample_data.index.max() < datetime.now().date():
            logger.warning("오늘 종가 데이터가 없어 업데이트를 건너뜁니다.")
            raise RuntimeError("오늘 종가 데이터가 없어 업데이트를 건너뜁니다.")
    except Exception as e:
        logger.warning(f"오늘 종가 확인 실패: {e}")
        raise RuntimeError(f"오늘 종가 확인 실패: {e}") from e

    logger.info("오늘 종가 데이터 확인 완료. 모든 종목 데이터 업데이트 시작...")
    try:
        krx_list = fdr.StockListing('KRX')
        symbol_col = next((c for c in ['Symbol', 'Code'] if c in krx_list.columns.tolist()), None)
        if not symbol_col:
            raise KeyError("KRX 리스트에서 'Symbol' 또는 'Code' 컬럼을 찾을 수 없습니다.")
        krx_symbols = krx_list[symbol_col].tolist()
    except Exception as e:
        logger.error(f"KRX 종목 리스트 로드 실패: {e}")
        raise RuntimeError(f"KRX 종목 리스트 로드 실패: {e}") from e

    # 멀티스레드로 데이터 업데이트
    with ThreadPoolExecutor(max_workers=5) as executor:
        futures = {executor.submit(fetch_fdr_and_save, symbol): symbol for symbol in krx_symbols}
        for future in as_completed(futures):
            future.result()  # 예외 발생 시 바로 중단
    logger.info("모든 종목 데이터 업데이트 완료.")

# ========================================================================
# 차트 그리기 및 Base64 변환
# ========================================================================
def plot_and_get_base64(chart_data_list, start_date, end_date, title):
    """
    차트 데이터를 받아 matplotlib로 차트 그린 후 Base64 문자열 반환
    """
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

# ========================================================================
# 유사 종목 분석 (상위 N)
# ========================================================================
def find_similar_chart(base_symbol, start_date, end_date, n_similar_stocks=5):
    """
    기준 종목과 기간을 받아 유사 종목 계산
    """
    save_all_data()  # 전체 데이터 업데이트

    logger.info(f"분석 시작 - 기준 종목: {base_symbol}, 시작일: {start_date}, 종료일: {end_date}")

    # KRX 종목 정보 가져오기
    try:
        krx_list = fdr.StockListing('KRX')
        symbol_col = next((c for c in ['Symbol', 'Code'] if c in krx_list.columns.tolist()), None)
        name_col = 'Name'
        krx_symbols = krx_list[symbol_col].tolist()
        krx_name_map = krx_list.set_index(symbol_col)[name_col].to_dict()
    except Exception as e:
        logger.error(f"KRX 종목 리스트 로드 실패: {e}")
        raise RuntimeError(f"KRX 종목 리스트 로드 실패: {e}") from e

    # 기준 종목 데이터
    try:
        base_data = pd.read_parquet(os.path.join(data_dir, f"{base_symbol}.parquet"))
        base_data = base_data.loc[start_date:end_date]
    except FileNotFoundError:
        logger.error(f"기준 종목({base_symbol}) 데이터 없음")
        raise RuntimeError(f"기준 종목({base_symbol}) 데이터 없음")

    if base_data.empty:
        raise RuntimeError(f"기준 종목 ({base_symbol}) 데이터가 없습니다.")

    base_close_prices = base_data['Close']
    similarities = []

    # 개별 종목 처리 함수
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
            base_norm = (base_close_prices - np.mean(base_close_prices)) / np.std(base_close_prices)
            stock_norm = (close_prices - np.mean(close_prices)) / np.std(close_prices)
            cos_sim = cosine_similarity(base_norm.values.reshape(1, -1), stock_norm.values.reshape(1, -1))
            return {"ticker": symbol, "name": krx_name_map.get(symbol, '알 수 없음'), "cosine_similarity": cos_sim.item()}
        except Exception as e:
            logger.error(f"{symbol} 처리 중 오류: {e}")
            return None

    # 멀티스레드 유사도 계산
    with ThreadPoolExecutor(max_workers=5) as executor:
        futures = {executor.submit(process_stock, symbol): symbol for symbol in krx_symbols}
        for future in as_completed(futures):
            result = future.result()
            if result:
                similarities.append(result)

    similarities.sort(key=lambda x: x['cosine_similarity'], reverse=True)
    top_n_similar_stocks = similarities[:n_similar_stocks]
    logger.info(f"분석 완료 - 상위 {len(top_n_similar_stocks)}개 종목 결과")
    return top_n_similar_stocks

# ========================================================================
# 단일 차트 비교
# ========================================================================
def plot_single_chart(base_symbol, compare_symbol, start_date, end_date):
    """
    기준 종목과 비교 종목 차트를 그리고 Base64 반환
    """
    logger.info(f"개별 차트 그리기: 기준={base_symbol}, 비교={compare_symbol}")
    chart_data_list = []

    # 기준 종목
    try:
        base_data = pd.read_parquet(os.path.join(data_dir, f"{base_symbol}.parquet"))
        base_data = base_data.loc[start_date:end_date]
        chart_data_list.append({'data': base_data, 'label': f"{base_symbol} (기준)", 'linewidth': 2.5})
    except Exception as e:
        raise RuntimeError(f"{base_symbol} 차트 로드 실패: {e}") from e

    # 비교 종목
    try:
        compare_data = pd.read_parquet(os.path.join(data_dir, f"{compare_symbol}.parquet"))
        compare_data = compare_data.loc[start_date:end_date]
        chart_data_list.append({'data': compare_data, 'label': f"{compare_symbol} (비교)", 'linewidth': 1.5})
    except Exception as e:
        raise RuntimeError(f"{compare_symbol} 차트 로드 실패: {e}") from e

    return plot_and_get_base64(chart_data_list, start_date, end_date, f"{base_symbol} vs {compare_symbol}")

# ========================================================================
# 메인 실행
# ========================================================================
def main():
    parser = argparse.ArgumentParser(description="주가 차트 유사성 분석 및 시각화")
    parser.add_argument("--base_symbol", type=str, required=True, help="비교 기준 종목 코드")
    parser.add_argument("--start_date", type=str, default="2023-01-01", help="시작일")
    parser.add_argument("--end_date", type=str, default=datetime.now().strftime('%Y-%m-%d'), help="종료일")
    parser.add_argument("--compare_symbol", type=str, help="단일 비교 종목 코드")
    parser.add_argument("--n_similar", type=int, default=5, help="유사 종목 수")
    args = parser.parse_args()

    try:
        if args.compare_symbol:
            image_data = plot_single_chart(args.base_symbol, args.compare_symbol, args.start_date, args.end_date)
            print(json.dumps({"image_data": image_data}))
        else:
            results = find_similar_chart(args.base_symbol, args.start_date, args.end_date, args.n_similar)
            print(json.dumps({"base_symbol": args.base_symbol, "similar_stocks": results}, ensure_ascii=False, indent=2))
    except Exception as e:
        logger.error(f"스크립트 실행 중 오류 발생: {e}")
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)  # 치명적 오류 시 즉시 종료

if __name__ == "__main__":
    main()
