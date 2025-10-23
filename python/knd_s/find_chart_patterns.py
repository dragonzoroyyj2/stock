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

# ========================================================================
# 환경 체크 함수
# ========================================================================
def check_environment():
    # ... (기존 check_environment 함수와 동일)
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
    for lib in ["pandas", "FinanceDataReader", "matplotlib", "backoff"]:
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

# ========================================================================
# 로깅 및 데이터 디렉토리 설정
# ========================================================================
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
# FinanceDataReader 재시도 및 캐싱 래퍼
# ========================================================================
ssl._create_default_https_context = ssl._create_unverified_context
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

@backoff.on_exception(backoff.expo, (RemoteDisconnected, ssl.SSLError, urllib3.exceptions.SSLError, urllib3.exceptions.MaxRetryError), max_tries=5, jitter=backoff.full_jitter)
def fetch_fdr_with_retry_with_cache(symbol, start=None, end=None):
    """
    FinanceDataReader를 사용하여 주식 데이터를 가져오고 캐시를 적용합니다.
    """
    cache_key = f"{symbol}_{start}_{end}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.pkl"
    cache_path = os.path.join(data_dir, cache_filename)
    
    # 캐시 확인
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

# ========================================================================
# 데이터 캐싱 (종목 리스트)
# ========================================================================
def get_stock_listing(data_dir):
    cache_path = os.path.join(data_dir, "stock_listing.pkl")
    if os.path.exists(cache_path) and (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))).days < 1:
        with open(cache_path, 'rb') as f:
            return pickle.load(f)
    
    krx = fdr.StockListing('KRX')
    with open(cache_path, 'wb') as f:
        pickle.dump(krx, f)
    return krx

# ========================================================================
# API 요청 제어 (Rate Limiting) 데코레이터
# ========================================================================
def rate_limit(per_second):
    """지정된 초당 요청 수 제한"""
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

# ========================================================================
# 종목 데이터 처리 워커 함수 (멀티프로세싱용)
# ========================================================================
@rate_limit(per_second=2) # 초당 2회 호출로 제한
def process_stock_data(args_tuple):
    symbol, start, end, krx = args_tuple
    try:
        logger.info(f"심볼 {symbol} 데이터 조회 중...")

        df = fetch_fdr_with_retry_with_cache(symbol, start=start, end=end)
        
        if df is None or len(df) < 2:
            logger.warning(f"심볼 {symbol}에 대한 데이터가 불충분합니다. (레코드 수: {len(df) if df is not None else 0})")
            return None
        
        downward_streak = 0
        for i in range(1, len(df)):
            if df['Close'].iloc[-i] < df['Close'].iloc[-i-1]:
                downward_streak += 1
            else:
                break
        
        if downward_streak == 0:
            return None

        name_series = krx.loc[krx['Symbol'] == symbol, 'Name']
        name = name_series.iloc[0] if not name_series.empty else "N/A"
        
        return {"ticker": symbol, "name": name, "streak": downward_streak}
    except Exception as e:
        logger.error(f"심볼 {symbol} 처리 중 오류: {e}", exc_info=True)
        return None

# ========================================================================
# 한글 폰트 설정
# ========================================================================
def set_korean_font():
    """
    플랫폼에 관계없이 한글 폰트를 설정합니다.
    """
    font_name = None
    if platform.system() == 'Windows':
        font_path = 'C:/Windows/Fonts/malgun.ttf'
        if os.path.exists(font_path):
            font_name = fm.FontProperties(fname=font_path).get_name()
    elif platform.system() == 'Darwin': # Mac OS
        font_name = 'AppleGothic'
    else: # Linux
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

# ========================================================================
# 차트 생성 및 캐싱 메서드
# ========================================================================
def generate_chart_with_cache(symbol, stock_name, df, start_date, end_date):
    """
    주식 데이터를 기반으로 차트를 생성하고 Base64로 인코딩된 이미지 데이터를 반환합니다.
    캐시 파일이 존재하면 캐시를 사용합니다.
    """
    cache_key = f"{symbol}_{start_date}_{end_date}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.png"
    cache_path = os.path.join(chart_cache_dir, cache_filename)

    # 캐시 파일 확인
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
# 메인 함수
# ========================================================================
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_symbol", default="ALL", help="조회할 종목 (ALL 또는 특정 종목 코드)")
    parser.add_argument("--start_date", required=True, help="조회 시작 날짜 (YYYY-MM-DD)")
    parser.add_argument("--end_date", required=True, help="조회 종료 날짜 (YYYY-MM-DD)")
    parser.add_argument("--topN", type=int, default=10, help="상위 N개 종목 (리스트 조회 시에만 사용)")
    parser.add_argument("--chart", action="store_true", help="개별 종목 차트 생성")
    args = parser.parse_args()

    try:
        check_environment()
        krx = get_stock_listing(data_dir)
        
        if args.chart:
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
                    args_list = [(symbol, args.start_date, args.end_date, krx) for symbol in base_list]
                    results = [res for res in pool.starmap(process_stock_data, args_list) if res]

            else:
                res = process_stock_data((args.base_symbol, args.start_date, args.end_date, krx))
                if res:
                    results.append(res)
            
            sorted_results = sorted(results, key=lambda x: x['streak'], reverse=True)
            top_N_results = sorted_results[:args.topN]
            print(json.dumps(top_N_results, ensure_ascii=False, indent=2))
            
    except Exception as e:
        logger.error(f"스크립트 실행 중 치명적인 오류 발생: {e}", exc_info=True)
        print(json.dumps({"error": str(e)}))

if __name__ == '__main__':
    freeze_support()
    main()

