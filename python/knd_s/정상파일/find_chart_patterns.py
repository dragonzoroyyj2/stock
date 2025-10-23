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
from multiprocessing import Pool, freeze_support
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

# ========================================================================
# 유틸리티 함수
# ========================================================================
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
        flush_log()
        return None # None 반환
    except Exception as e:
        error_msg = f"일반 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        flush_log()
        return None # None 반환

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
        flush_log()
        return None
    except Exception as e:
        error_msg = f"KRX 종목 목록 조회 중 일반 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        flush_log()
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
        flush_log()
        return None

# ========================================================================
# 차트 패턴 감지 함수들
# ========================================================================
def detect_head_and_shoulders(df):
    """헤드 앤 숄더 패턴을 감지합니다."""
    if len(df) < 60: return False
    peaks, _ = find_peaks(df['Close'], distance=10, prominence=df['Close'].max() * 0.02)
    if len(peaks) < 3: return False

    last_60_days = df.iloc[-60:].index
    peaks_in_last_60 = peaks[np.isin(df.index[peaks], last_60_days)]
    if len(peaks_in_last_60) < 3: return False

    last_three_peaks = peaks_in_last_60[-3:]
    head_idx, left_shoulder_idx, right_shoulder_idx = last_three_peaks[1], last_three_peaks[0], last_three_peaks[2]
    
    # 순서 확인
    if not (left_shoulder_idx < head_idx < right_shoulder_idx):
        return False

    # 봉우리의 가격
    head_price = df['Close'].iloc[head_idx]
    left_shoulder_price = df['Close'].iloc[left_shoulder_idx]
    right_shoulder_price = df['Close'].iloc[right_shoulder_idx]

    # 헤드가 양쪽 어깨보다 높고, 양쪽 어깨의 높이가 비슷해야 함
    if head_price > left_shoulder_price and head_price > right_shoulder_price:
        if abs(left_shoulder_price - right_shoulder_price) / max(left_shoulder_price, right_shoulder_price) < 0.15:
            return True
    return False

def detect_inverse_head_and_shoulders(df):
    """역 헤드 앤 숄더 패턴을 감지합니다."""
    if len(df) < 60: return False
    valleys, _ = find_peaks(-df['Close'], distance=10, prominence=df['Close'].max() * 0.02)
    if len(valleys) < 3: return False

    last_60_days = df.iloc[-60:].index
    valleys_in_last_60 = valleys[np.isin(df.index[valleys], last_60_days)]
    if len(valleys_in_last_60) < 3: return False

    last_three_valleys = valleys_in_last_60[-3:]
    head_idx, left_shoulder_idx, right_shoulder_idx = last_three_valleys[1], last_three_valleys[0], last_three_valleys[2]

    # 순서 확인
    if not (left_shoulder_idx < head_idx < right_shoulder_idx):
        return False

    # 골짜기의 가격
    head_price = df['Close'].iloc[head_idx]
    left_shoulder_price = df['Close'].iloc[left_shoulder_idx]
    right_shoulder_price = df['Close'].iloc[right_shoulder_idx]

    # 헤드가 양쪽 어깨보다 낮고, 양쪽 어깨의 높이가 비슷해야 함
    if head_price < left_shoulder_price and head_price < right_shoulder_price:
        if abs(left_shoulder_price - right_shoulder_price) / max(left_shoulder_price, right_shoulder_price) < 0.15:
            return True
    return False

def worker_find_patterns(params):
    """멀티프로세싱 워커 함수."""
    symbol, stock_name, start, end, patterns_to_detect = params
    try:
        df = fetch_fdr_with_retry_with_cache(symbol, start=start, end=end)
        if df is None or df.empty:
            logger.warning(f"데이터 없음: {symbol} ({stock_name})")
            return None

        found_patterns = []
        if 'head_and_shoulders' in patterns_to_detect and detect_head_and_shoulders(df):
            found_patterns.append('head_and_shoulders')
        if 'inverse_head_and_shoulders' in patterns_to_detect and detect_inverse_head_and_shoulders(df):
            found_patterns.append('inverse_head_and_shoulders')
        
        # TODO: double_top, double_bottom, cup_and_handle 패턴 감지 로직 추가

        if found_patterns:
            result = {
                "symbol": symbol,
                "name": stock_name,
                "patterns": found_patterns,
                "chart": generate_chart_with_cache(symbol, stock_name, df, start, end)
            }
            logger.info(f"패턴 감지: {symbol} ({stock_name}) - {', '.join(found_patterns)}")
            return result
        return None
    except Exception as e:
        logger.error(f"처리 중 오류 발생: {symbol} - {e}")
        return None

def find_patterns_parallel(start_date, end_date, patterns_to_detect, topN):
    """멀티프로세싱을 사용하여 패턴을 감지합니다."""
    krx = get_stock_listing(data_dir)
    if krx is None:
        return []
    target_stocks = krx.head(topN)

    params = [(row['Code'], row['Name'], start_date, end_date, patterns_to_detect) for _, row in target_stocks.iterrows()]
    
    with Pool() as pool:
        results = pool.map(worker_find_patterns, params)
    
    return [r for r in results if r is not None]

def main():
    parser = argparse.ArgumentParser(description="주식 차트 패턴 감지 및 차트 생성 스크립트")
    parser.add_argument("--start_date", required=True, help="시작 날짜 (YYYY-MM-DD)")
    parser.add_argument("--end_date", required=True, help="종료 날짜 (YYYY-MM-DD)")
    parser.add_argument("--pattern", help="감지할 패턴 ('head_and_shoulders', 'inverse_head_and_shoulders' 등)")
    parser.add_argument("--topN", type=int, default=100, help="상위 N개 종목 (패턴 감지 시)")
    parser.add_argument("--parallel", action="store_true", help="멀티프로세싱으로 패턴 감지")
    parser.add_argument("--base_symbol", help="차트 생성할 종목 코드")
    parser.add_argument("--chart", action="store_true", help="차트 생성 모드")

    try:
        args = parser.parse_args()
        check_environment()
        
        start_date = args.start_date
        end_date = args.end_date

        if args.chart and args.base_symbol:
            df = fetch_fdr_with_retry_with_cache(args.base_symbol, start=start_date, end=end_date)
            if df is None or df.empty:
                raise ValueError(f"데이터 없음: {args.base_symbol}")
            
            stock_listing = get_stock_listing(data_dir)
            stock_name = stock_listing[stock_listing['Code'] == args.base_symbol]['Name'].iloc[0] if not stock_listing.empty else args.base_symbol

            image_data = generate_chart_with_cache(args.base_symbol, stock_name, df, start_date, end_date)
            result = {"image_data": image_data}
            print(json.dumps(result))
        
        elif args.pattern:
            patterns_to_detect = args.pattern.split(',')
            if args.parallel:
                results = find_patterns_parallel(start_date, end_date, patterns_to_detect, args.topN)
            else:
                # find_patterns_sequential 함수가 없으므로 추후 구현 필요
                raise NotImplementedError("find_patterns_sequential 함수가 구현되지 않았습니다.")
            
            print(json.dumps(results, ensure_ascii=False))

    except Exception as e:
        error_msg = f"메인 로직 처리 중 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        flush_log()
        sys.exit(1)

    finally:
        flush_log()

if __name__ == "__main__":
    freeze_support()
    main()
