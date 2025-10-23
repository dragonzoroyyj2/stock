# -*- coding: utf-8 -*-
import os
import sys
import argparse
import logging
import json
import platform
import ssl
import urllib3
from http.client import RemoteDisconnected
import base64
from io import BytesIO
import pandas as pd
import FinanceDataReader as fdr
import matplotlib.pyplot as plt
import backoff
import pickle
from datetime import datetime, timedelta
from multiprocessing import Pool, freeze_support
import time
import hashlib
from functools import wraps
import matplotlib.font_manager as fm
from matplotlib import rc
import numpy as np
from scipy.signal import find_peaks

# ========================================================================
# 환경 설정 및 로깅 (기존 코드와 동일)
# ========================================================================
# ... (이전 find_chart_patterns.py 코드의 환경 체크, 로깅, 데이터 디렉토리 설정 부분) ...
def check_environment():
    # ...
    logger = logging.getLogger("env_check")
    logger.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stderr)
    formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    
    logger.info("=== 환경 체크 시작 ===")
    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")
    logger.info(f"Python Version: {sys.version}")

    missing_libs = []
    for lib in ["pandas", "FinanceDataReader", "matplotlib", "backoff", "numpy", "scipy"]:
        try:
            __import__(lib)
        except ImportError:
            missing_libs.append(lib)
    if missing_libs:
        logger.warning(f"설치되지 않은 라이브러리: {', '.join(missing_libs)}")
        raise ImportError(f"다음 라이브러리를 설치해야 합니다: {', '.join(missing_libs)}")
    else:
        logger.info("필수 라이브러리 모두 설치됨")

    logger.info("=== 환경 체크 완료 ===\n")

script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "log", "my_log_file.log")
data_dir = os.path.join(script_dir, "stock_data")
chart_cache_dir = os.path.join(data_dir, "charts")
os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)
os.makedirs(chart_cache_dir, exist_ok=True)

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
# FinanceDataReader 재시도 및 캐싱 (기존 코드와 동일)
# ========================================================================
ssl._create_default_https_context = ssl._create_unverified_context
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

@backoff.on_exception(backoff.expo, (RemoteDisconnected, ssl.SSLError, urllib3.exceptions.SSLError, urllib3.exceptions.MaxRetryError), max_tries=5, jitter=backoff.full_jitter)
def fetch_fdr_with_retry_with_cache(symbol, start=None, end=None):
    # ... (기존 fetch_fdr_with_retry_with_cache 함수와 동일) ...
    cache_key = f"{symbol}_{start}_{end}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.pkl"
    cache_path = os.path.join(data_dir, cache_filename)
    
    if os.path.exists(cache_path):
        if (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))) < timedelta(days=1):
            with open(cache_path, 'rb') as f:
                logger.info(f"캐시에서 {symbol} 데이터 불러옴: {cache_path}")
                return pickle.load(f)
    try:
        df = fdr.DataReader(symbol, start=start, end=end)
        if not df.empty:
            with open(cache_path, 'wb') as f:
                pickle.dump(df, f)
            logger.info(f"FinanceDataReader에서 {symbol} 데이터 새로 가져와 캐시에 저장")
        return df
    except Exception as e:
        raise RuntimeError(f"{symbol} 데이터 조회 중 오류: {e}") from e

def get_stock_listing(data_dir):
    # ... (기존 get_stock_listing 함수와 동일) ...
    cache_path = os.path.join(data_dir, "stock_listing.pkl")
    if os.path.exists(cache_path) and (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))).days < 1:
        with open(cache_path, 'rb') as f:
            return pickle.load(f)
    krx = fdr.StockListing('KRX')
    with open(cache_path, 'wb') as f:
        pickle.dump(krx, f)
    return krx

def rate_limit(per_second):
    # ... (기존 rate_limit 데코레이터와 동일) ...
    def decorator(func):
        last_call_time = 0
        @wraps(func)
        def wrapper(*args, **kwargs):
            nonlocal last_call_time
            current_time = time.time()
            elapsed_time = current_time - last_call_time
            if elapsed_time < 1 / per_second:
                time.sleep(1 / per_second - elapsed_time)
            last_call_time = time.time()
            return func(*args, **kwargs)
        return wrapper
    return decorator

def set_korean_font():
    # ... (기존 set_korean_font 함수와 동일) ...
    font_name = None
    if platform.system() == 'Windows':
        font_path = 'C:/Windows/Fonts/malgun.ttf'
        if os.path.exists(font_path):
            font_name = fm.FontProperties(fname=font_path).get_name()
    elif platform.system() == 'Darwin':
        font_name = 'AppleGothic'
    else:
        fonts = fm.findSystemFonts(fontpaths=None, fontext='ttf')
        for font in fonts:
            if 'nanum' in os.path.basename(font).lower():
                font_name = fm.FontProperties(fname=font).get_name()
                break
    if font_name:
        rc('font', family=font_name)
    else:
        logger.warning("한글 폰트를 찾을 수 없습니다. 기본 폰트로 대체합니다.")
        rc('font', family='DejaVu Sans')
    plt.rcParams['axes.unicode_minus'] = False

def generate_chart_with_cache(symbol, stock_name, df, start_date, end_date):
    # ... (기존 generate_chart_with_cache 함수와 동일) ...
    cache_key = f"{symbol}_{start_date}_{end_date}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.png"
    cache_path = os.path.join(chart_cache_dir, cache_filename)

    if os.path.exists(cache_path):
        logger.info(f"캐시에서 차트 이미지 불러옴: {cache_path}")
        with open(cache_path, 'rb') as f:
            img_base64 = base64.b64encode(f.read()).decode()
        return img_base64
    try:
        set_korean_font()
        plt.figure(figsize=(10, 5))
        plt.plot(df.index, df['Close'], label=stock_name)
        plt.title(f"{stock_name} 종가 차트 ({start_date} ~ {end_date})")
        plt.xlabel("날짜")
        plt.ylabel("종가")
        plt.grid(True)
        plt.tight_layout()
        buffer = BytesIO()
        plt.savefig(buffer, format='png')
        plt.savefig(cache_path, format='png')
        plt.close()
        buffer.seek(0)
        img_base64 = base64.b64encode(buffer.read()).decode()
        logger.info(f"새 차트 생성 후 캐시에 저장: {cache_path}")
        return img_base64
    except Exception as e:
        logger.error(f"차트 생성 중 오류 발생: {e}", exc_info=True)
        return None

# ========================================================================
# 차트 패턴 감지 함수들
# ========================================================================
def detect_head_and_shoulders(df):
    if len(df) < 60: return False
    
    # 고점 찾기
    peaks, _ = find_peaks(df['Close'], distance=20)
    if len(peaks) < 3: return False
    
    # 마지막 3개의 고점
    peaks_last_three = peaks[peaks > len(df) - 60]
    if len(peaks_last_three) < 3: return False
    
    head_idx = peaks_last_three[1]
    left_shoulder_idx = peaks_last_three[0]
    right_shoulder_idx = peaks_last_three[2]
    
    head_price = df['Close'].iloc[head_idx]
    left_shoulder_price = df['Close'].iloc[left_shoulder_idx]
    right_shoulder_price = df['Close'].iloc[right_shoulder_idx]
    
    # 헤드가 양 어깨보다 높고, 양 어깨가 비슷한 높이인지 확인
    if head_price > left_shoulder_price and head_price > right_shoulder_price:
        if abs(left_shoulder_price - right_shoulder_price) / left_shoulder_price < 0.1: # 10% 오차 허용
            return True
            
    return False

def detect_inverse_head_and_shoulders(df):
    if len(df) < 60: return False
    
    # 저점 찾기
    valleys, _ = find_peaks(-df['Close'], distance=20)
    if len(valleys) < 3: return False
    
    valleys_last_three = valleys[valleys > len(df) - 60]
    if len(valleys_last_three) < 3: return False
    
    head_idx = valleys_last_three[1]
    left_shoulder_idx = valleys_last_three[0]
    right_shoulder_idx = valleys_last_three[2]
    
    head_price = df['Close'].iloc[head_idx]
    left_shoulder_price = df['Close'].iloc[left_shoulder_idx]
    right_shoulder_price = df['Close'].iloc[right_shoulder_idx]
    
    # 헤드가 양 어깨보다 낮고, 양 어깨가 비슷한 높이인지 확인
    if head_price < left_shoulder_price and head_price < right_shoulder_price:
        if abs(left_shoulder_price - right_shoulder_price) / left_shoulder_price < 0.1: # 10% 오차 허용
            return True
            
    return False

def detect_double_top(df):
    if len(df) < 40: return False
    
    # 고점 찾기
    peaks, _ = find_peaks(df['Close'], distance=10)
    if len(peaks) < 2: return False
    
    last_two_peaks = peaks[peaks > len(df) - 40][-2:]
    if len(last_two_peaks) < 2: return False
    
    peak1_price = df['Close'].iloc[last_two_peaks[0]]
    peak2_price = df['Close'].iloc[last_two_peaks[1]]
    
    # 두 고점이 비슷한 높이인지 확인
    if abs(peak1_price - peak2_price) / peak1_price < 0.05: # 5% 오차 허용
        return True
    
    return False

def detect_double_bottom(df):
    if len(df) < 40: return False
    
    # 저점 찾기
    valleys, _ = find_peaks(-df['Close'], distance=10)
    if len(valleys) < 2: return False
    
    last_two_valleys = valleys[valleys > len(df) - 40][-2:]
    if len(last_two_valleys) < 2: return False
    
    valley1_price = df['Close'].iloc[last_two_valleys[0]]
    valley2_price = df['Close'].iloc[last_two_valleys[1]]
    
    # 두 저점이 비슷한 높이인지 확인
    if abs(valley1_price - valley2_price) / valley1_price < 0.05: # 5% 오차 허용
        return True
    
    return False

def detect_cup_and_handle(df):
    if len(df) < 120: return False # 긴 기간 필요
    
    # 컵 부분 (U자형 바닥)
    cup_length = 60
    cup_df = df.iloc[-cup_length:]
    
    # 바닥이 둥근지 확인 (예: 중간 지점이 가장 낮은지)
    mid_idx = cup_length // 2
    if cup_df['Close'].iloc[mid_idx] < cup_df['Close'].iloc[0] and cup_df['Close'].iloc[mid_idx] < cup_df['Close'].iloc[-1]:
        # 핸들 부분 (작은 하락 후 반등)
        handle_df = df.iloc[-20:] # 최근 20일
        if (handle_df['Close'].iloc[-1] > handle_df['Close'].iloc[0]) and \
           (df['Close'].iloc[-1] < df['Close'].iloc[-1 - 20]): # 핸들 상승, 하지만 컵 상단보다 낮은지
           return True
           
    return False

# 패턴 감지 함수 딕셔너리
PATTERN_DETECTORS = {
    'head_and_shoulders': detect_head_and_shoulders,
    'inverse_head_and_shoulders': detect_inverse_head_and_shoulders,
    'double_top': detect_double_top,
    'double_bottom': detect_double_bottom,
    'cup_and_handle': detect_cup_and_handle,
}

# ========================================================================
# 종목 데이터 처리 워커 함수 (멀티프로세싱용)
# ========================================================================
@rate_limit(per_second=2)
def process_pattern_detection(args_tuple):
    symbol, start, end, krx, pattern_type = args_tuple
    try:
        logger.info(f"심볼 {symbol} 데이터 조회 중... 패턴: {pattern_type}")

        df = fetch_fdr_with_retry_with_cache(symbol, start=start, end=end)
        
        if df is None or len(df) < 60:
            logger.warning(f"심볼 {symbol}에 대한 데이터가 불충분합니다. (레코드 수: {len(df) if df is not None else 0})")
            return None
        
        detector = PATTERN_DETECTORS.get(pattern_type)
        if detector and detector(df):
            name_series = krx.loc[krx['Symbol'] == symbol, 'Name']
            name = name_series.iloc[0] if not name_series.empty else "N/A"
            return {"ticker": symbol, "name": name, "pattern": pattern_type}
        
        return None
    except Exception as e:
        logger.error(f"심볼 {symbol} 처리 중 오류: {e}", exc_info=True)
        return None

# ========================================================================
# 메인 함수
# ========================================================================
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_symbol", default="ALL", help="조회할 종목 (ALL 또는 특정 종목 코드)")
    parser.add_argument("--start_date", required=True, help="조회 시작 날짜 (YYYY-MM-DD)")
    parser.add_argument("--end_date", required=True, help="조회 종료 날짜 (YYYY-MM-DD)")
    parser.add_argument("--pattern", required=True, choices=list(PATTERN_DETECTORS.keys()), help="감지할 차트 패턴")
    parser.add_argument("--topN", type=int, default=10, help="상위 N개 종목 (리스트 조회 시에만 사용)")
    parser.add_argument("--chart", action="store_true", help="개별 종목 차트 생성")
    args = parser.parse_args()

    try:
        check_environment()
        krx = get_stock_listing(data_dir)
        
        if args.chart:
            # ... (기존 차트 생성 로직과 동일) ...
            if args.base_symbol == "ALL":
                raise ValueError("차트 생성 모드에서는 기준 종목(--base_symbol)이 'ALL'일 수 없습니다.")
            symbol = args.base_symbol
            name_series = krx.loc[krx['Symbol'] == symbol, 'Name']
            stock_name = name_series.iloc[0] if not name_series.empty else symbol
            df = fetch_fdr_with_retry_with_cache(symbol, start=args.start_date, end=args.end_date)
            if df is not None and not df.empty:
                img_base64 = generate_chart_with_cache(symbol, stock_name, df, args.start_date, args.end_date)
                if img_base64:
                    print(json.dumps({"image_data": img_base64}))
                else:
                    print(json.dumps({"error": "차트 생성에 실패했습니다."}))
            else:
                print(json.dumps({"error": f"{symbol}에 대한 데이터가 없습니다."}))
        else:
            if args.base_symbol != "ALL":
                if args.base_symbol not in krx['Symbol'].values:
                    raise ValueError(f"{args.base_symbol} 종목이 존재하지 않습니다.")
                base_list = [args.base_symbol]
            else:
                base_list = krx['Symbol'].tolist()

            results = []
            if args.base_symbol == "ALL":
                num_processes = os.cpu_count() or 1
                logger.info(f"멀티프로세싱으로 {len(base_list)}개 종목 처리 시작 (프로세스 수: {num_processes})")

                with Pool(processes=num_processes) as pool:
                    args_list = [(symbol, args.start_date, args.end_date, krx, args.pattern) for symbol in base_list]
                    results = [res for res in pool.starmap(process_pattern_detection, args_list) if res]
            else:
                res = process_pattern_detection((args.base_symbol, args.start_date, args.end_date, krx, args.pattern))
                if res:
                    results.append(res)
            
            # 패턴 감지 결과는 정렬 기준이 없으므로 topN만 적용
            top_N_results = results[:args.topN]
            print(json.dumps(top_N_results, ensure_ascii=False, indent=2))
            
    except Exception as e:
        logger.error(f"스크립트 실행 중 치명적인 오류 발생: {e}", exc_info=True)
        print(json.dumps({"error": str(e)}))

if __name__ == '__main__':
    freeze_support()
    main()
