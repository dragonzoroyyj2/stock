# C:\LocBootProject\workspace\MyBaseLink\python\find_chart_patterns.py

import os
import sys
import argparse
import logging
import json
import platform
import ssl
import urllib3
import requests
from http.client import RemoteDisconnected
from urllib3.exceptions import MaxRetryError, SSLError as Urllib3SSLError
from requests.exceptions import ConnectionError, SSLError as RequestsSSLError
import base64
from io import BytesIO
import pandas as pd
import FinanceDataReader as fdr
import matplotlib.pyplot as plt
import pickle
from datetime import datetime, timedelta
from multiprocessing import Pool, freeze_support, cpu_count
import time
import hashlib
import matplotlib.font_manager as fm
from matplotlib import rc
import numpy as np
from scipy.signal import find_peaks

# ========================================================================
# 환경 설정 및 로깅
# ========================================================================
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')

script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "log", "my_log_file.log")
data_dir = os.path.join(script_dir, "stock_data")
chart_cache_dir = os.path.join(data_dir, "charts")
os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)
os.makedirs(chart_cache_dir, exist_ok=True)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s",
                    handlers=[logging.FileHandler(log_file_path, mode='a', encoding='utf-8')])
logging.getLogger('fdr.reader').setLevel(logging.CRITICAL)

logger = logging.getLogger()

ssl._create_default_https_context = ssl._create_unverified_context
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

def flush_log():
    """로그 버퍼를 강제로 비워 파일에 기록합니다."""
    for handler in logger.handlers:
        handler.flush()
    logging.shutdown()

def check_environment():
    """스크립트 실행 환경을 확인합니다."""
    logger.info("=== 환경 체크 시작 ===")
    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")
    logger.info(f"Python Version: {sys.version}")

    missing_libs = []
    for lib in ["pandas", "FinanceDataReader", "matplotlib", "requests", "numpy", "scipy"]:
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
    flush_log()

def fetch_fdr_with_retry_with_cache(symbol, start=None, end=None):
    """캐시를 사용하여 FinanceDataReader에서 주식 데이터를 안전하게 가져옵니다."""
    cache_key = f"{symbol}_{start}_{end}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.pkl"
    cache_path = os.path.join(data_dir, cache_filename)

    if os.path.exists(cache_path):
        if (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))) < timedelta(days=1):
            try:
                with open(cache_path, 'rb') as f:
                    logger.info(f"캐시된 데이터 로드: {symbol}")
                    return pickle.load(f)
            except Exception:
                logger.error(f"캐시 파일 손상. 삭제 후 재시도: {cache_path}")
                os.remove(cache_path)

    try:
        logger.info(f"FinanceDataReader에서 데이터 가져오기 시작: {symbol}")
        df = fdr.DataReader(symbol, start=start, end=end)
        if not df.empty:
            with open(cache_path, 'wb') as f:
                pickle.dump(df, f)
            logger.info(f"데이터 가져오기 및 캐싱 완료: {symbol}")
        return df
    except (RemoteDisconnected, ssl.SSLError, Urllib3SSLError, MaxRetryError, ConnectionError, RequestsSSLError) as e:
        error_msg = f"통신(네트워크/SSL) 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None
    except Exception as e:
        error_msg = f"일반 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None

def get_stock_listing(data_dir):
    """캐시를 사용하여 KRX 상장 종목 목록을 가져옵니다."""
    cache_path = os.path.join(data_dir, "stock_listing.pkl")
    if os.path.exists(cache_path) and (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))).days < 1:
        try:
            with open(cache_path, 'rb') as f:
                return pickle.load(f)
        except Exception:
            os.remove(cache_path)
    try:
        logger.info("KRX 종목 목록 조회 시작")
        krx = fdr.StockListing('KRX')
        with open(cache_path, 'wb') as f:
            pickle.dump(krx, f)
        logger.info("KRX 종목 목록 조회 및 캐싱 완료")
        return krx
    except (RemoteDisconnected, ssl.SSLError, Urllib3SSLError, MaxRetryError, ConnectionError, RequestsSSLError) as e:
        error_msg = f"KRX 종목 목록 조회 중 통신(네트워크/SSL) 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None
    except Exception as e:
        error_msg = f"KRX 종목 목록 조회 중 일반 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None

def set_korean_font():
    """Matplotlib에서 한글이 깨지지 않도록 폰트를 설정합니다."""
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
        rc('font', family='DejaVu Sans')
    plt.rcParams['axes.unicode_minus'] = False

def generate_chart_with_cache(symbol, stock_name, df, start_date, end_date):
    """주식 차트를 생성하고 캐시합니다."""
    cache_key = f"{symbol}_{start_date}_{end_date}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.png"
    cache_path = os.path.join(chart_cache_dir, cache_filename)
    if os.path.exists(cache_path):
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
        return img_base64
    except Exception as e:
        error_msg = f"차트 생성 중 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None

# ========================================================================
# 차트 패턴 감지 함수들
# ========================================================================
def detect_head_and_shoulders(df):
    """헤드 앤 숄더 패턴을 감지합니다."""
    # 최소 60일의 데이터가 필요합니다.
    if len(df) < 60:
        return False
    
    # 최근 60일 데이터만 사용하여 피크를 찾습니다.
    df_last_60 = df.iloc[-60:].copy()
    
    # find_peaks를 사용하여 피크를 찾습니다.
    # distance: 피크 사이의 최소 거리 (예: 10일)
    # prominence: 피크의 상대적 중요도. 피크가 주변보다 얼마나 높은지를 나타냄.
    peaks_indices, _ = find_peaks(df_last_60['Close'], distance=10, prominence=df_last_60['Close'].max() * 0.02)
    
    if len(peaks_indices) < 3:
        return False

    # 피크를 값(높이) 기준으로 내림차순 정렬
    peaks_values = df_last_60['Close'].iloc[peaks_indices]
    sorted_peaks_indices = peaks_indices[np.argsort(peaks_values)[::-1]]

    # 가장 높은 피크를 헤드 후보로 설정
    head_idx = sorted_peaks_indices[0]
    head_price = df_last_60['Close'].iloc[head_idx]
    
    # 나머지 피크 중 헤드 양옆에 위치한 두 피크를 어깨 후보로 찾기
    left_shoulder_idx = -1
    right_shoulder_idx = -1
    left_shoulder_price = -1
    right_shoulder_price = -1
    
    for idx in sorted_peaks_indices:
        if idx < head_idx and left_shoulder_idx == -1:
            left_shoulder_idx = idx
            left_shoulder_price = df_last_60['Close'].iloc[idx]
        elif idx > head_idx and right_shoulder_idx == -1:
            right_shoulder_idx = idx
            right_shoulder_price = df_last_60['Close'].iloc[idx]
        if left_shoulder_idx != -1 and right_shoulder_idx != -1:
            break
            
    if left_shoulder_idx == -1 or right_shoulder_idx == -1:
        return False

    # 헤드 앤 숄더 패턴의 조건 검증
    # 1. 헤드가 가장 높아야 함
    if not (head_price > left_shoulder_price and head_price > right_shoulder_price):
        return False

    # 2. 어깨의 높이가 비슷해야 함 (헤드와 어깨의 가격 차이의 10% 이내)
    if abs(left_shoulder_price - right_shoulder_price) > (head_price - min(left_shoulder_price, right_shoulder_price)) * 0.10:
        return False
    
    # 3. 넥라인(어깨 사이의 저점) 아래로 주가가 이탈했는지 확인
    # 왼쪽 어깨와 헤드 사이의 저점 찾기
    left_trough = df_last_60['Close'].iloc[left_shoulder_idx:head_idx].min()
    # 헤드와 오른쪽 어깨 사이의 저점 찾기
    right_trough = df_last_60['Close'].iloc[head_idx:right_shoulder_idx].min()

    # 넥라인은 두 저점을 연결한 선으로 가정. 단순화를 위해 낮은 저점 값을 넥라인으로 설정
    neckline_price = min(left_trough, right_trough)
    
    # 최근 주가가 넥라인 아래에 있는지 확인
    if df_last_60['Close'].iloc[-1] < neckline_price:
        return True

    return False


# ========================================================================
# 멀티프로세싱을 위한 워커 함수
# ========================================================================
def chart_pattern_worker(params):
    """
    멀티프로세싱을 위한 워커 함수.
    개별 종목에 대한 패턴을 감지하고 결과를 반환합니다.
    """
    symbol, stock_name, start, end, pattern_name = params
    
    logger.info(f"[{os.getpid()}] 종목 데이터 처리 시작: {symbol} ({stock_name})")
    df = fetch_fdr_with_retry_with_cache(symbol, start, end)
    
    if df is None or df.empty:
        logger.warning(f"[{os.getpid()}] 데이터 없음 또는 가져오기 실패: {symbol}")
        return None

    try:
        if pattern_name == 'head_and_shoulders' and detect_head_and_shoulders(df):
            result = {
                "symbol": symbol,
                "name": stock_name,
                "pattern": pattern_name,
                "start_date": df.index[0].strftime('%Y-%m-%d'),
                "end_date": df.index[-1].strftime('%Y-%m-%d')
            }
            logger.info(f"[{os.getpid()}] 패턴 감지 성공: {symbol}")
            return result
    except Exception as e:
        logger.error(f"[{os.getpid()}] 패턴 감지 중 오류 발생: {symbol}, 오류: {e}", exc_info=True)
    
    return None

# ========================================================================
# 메인 함수 및 스크립트 실행
# ========================================================================
def main():
    """
    메인 실행 로직.
    명령줄 인수에 따라 차트 패턴 감지 또는 차트 생성을 수행합니다.
    """
    freeze_support()
    parser = argparse.ArgumentParser(description='Find chart patterns or generate charts for stock data.')
    parser.add_argument('--start_date', type=str, required=True, help='Start date (YYYY-MM-DD)')
    parser.add_argument('--end_date', type=str, required=True, help='End date (YYYY-MM-DD)')
    parser.add_argument('--pattern', type=str, help='Pattern to find (e.g., head_and_shoulders)')
    parser.add_argument('--topN', type=int, default=100, help='Number of top patterns to return')
    parser.add_argument('--parallel', action='store_true', help='Use multiprocessing for parallel pattern search')
    parser.add_argument('--base_symbol', type=str, help='Base symbol for chart generation')
    parser.add_argument('--chart', action='store_true', help='Generate chart image')
    
    args = parser.parse_args()
    
    if args.chart and args.base_symbol:
        # 단일 종목 차트 생성
        krx_listing = get_stock_listing(data_dir)
        if krx_listing is None:
            print(json.dumps({"error": "종목 목록을 가져오는 데 실패했습니다."}), file=sys.stderr)
            sys.exit(1)
            
        stock_info = krx_listing[krx_listing['Symbol'] == args.base_symbol]
        if stock_info.empty:
            print(json.dumps({"error": f"종목을 찾을 수 없습니다: {args.base_symbol}"}), file=sys.stderr)
            sys.exit(1)
        stock_name = stock_info.iloc[0]['Name']
        
        df = fetch_fdr_with_retry_with_cache(args.base_symbol, args.start_date, args.end_date)
        if df is None or df.empty:
            print(json.dumps({"error": f"데이터를 가져오는 데 실패했습니다: {args.base_symbol}"}), file=sys.stderr)
            sys.exit(1)
        
        img_base64 = generate_chart_with_cache(args.base_symbol, stock_name, df, args.start_date, args.end_date)
        
        if img_base64:
            result = {"image_data": img_base64}
            print(json.dumps(result, ensure_ascii=False))
        else:
            print(json.dumps({"error": "차트 이미지 생성 실패"}), file=sys.stderr)
            
    elif args.pattern:
        # 차트 패턴 감지
        krx_listing = get_stock_listing(data_dir)
        if krx_listing is None:
            print(json.dumps({"error": "종목 목록을 가져오는 데 실패했습니다."}), file=sys.stderr)
            sys.exit(1)
            
        symbols = [(row['Symbol'], row['Name'], args.start_date, args.end_date, args.pattern) for _, row in krx_listing.iterrows()]
        
        results = []
        if args.parallel:
            num_processes = cpu_count()
            logger.info(f"멀티프로세싱({num_processes}개)을 사용하여 차트 패턴 감지 시작")
            with Pool(processes=num_processes) as pool:
                all_results = pool.map(chart_pattern_worker, symbols)
                results = [res for res in all_results if res is not None]
        else:
            logger.info("단일 프로세스를 사용하여 차트 패턴 감지 시작")
            for symbol_params in symbols:
                res = chart_pattern_worker(symbol_params)
                if res:
                    results.append(res)
        
        # 결과 반환 (topN 적용)
        if len(results) > args.topN:
            results = results[:args.topN]
            
        print(json.dumps(results, ensure_ascii=False))
        
    else:
        parser.print_help()
        sys.exit(1)

if __name__ == "__main__":
    main()
    flush_log()
