#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
find_similar_full_refactored.py

- 기본 원칙: 로컬에 저장된 데이터(krx_list_full.json + 각 종목 .parquet) 기반으로만 동작.
- 네트워크(FinanceDataReader)를 통한 업데이트는 명시적 옵션 --update_data 로만 시도.
- 내부망 환경에서 안전하게 동작하도록 verify=False, urllib3 경고 비활성화 처리(필요시).
"""

import platform
import sys
import subprocess
import logging
import os
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
import argparse
import time
import traceback

# --- Optional imports (FinanceDataReader) ---
try:
    import FinanceDataReader as fdr
    FDR_AVAILABLE = True
except Exception:
    FDR_AVAILABLE = False

import pandas as pd
import numpy as np
from sklearn.metrics.pairwise import cosine_similarity
import matplotlib.pyplot as plt
import base64
from io import BytesIO
import urllib3
import backoff
from http.client import RemoteDisconnected

# SSL 경고 비활성화 (내부망에서 사내 CA/프록시로 인해 경고가 뜰 때)
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# -----------------------
# 기본 경로 설정
# -----------------------
script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "data", "my_log_file.log")
data_dir = os.path.join(script_dir, "stock_data")
default_krx_json = os.path.join(script_dir, "krx_list_full.json")

# 디렉터리 보장
os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)

# -----------------------
# 로깅 설정 (중복 핸들러 방지)
# -----------------------
logger = logging.getLogger("similarity")
logger.setLevel(logging.INFO)
if not logger.handlers:
    formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    fh = logging.FileHandler(log_file_path, encoding='utf-8')
    fh.setFormatter(formatter)
    sh = logging.StreamHandler(sys.stderr)
    sh.setFormatter(formatter)
    logger.addHandler(fh)
    logger.addHandler(sh)

# -----------------------
# 환경 체크 (간단)
# -----------------------
def check_environment():
    env_logger = logging.getLogger("env_check")
    env_logger.setLevel(logging.INFO)
    if not any(h.get_name() == "env_check" for h in env_logger.handlers):
        # 별도 핸들러 없이 메인 로거로 출력되게 함 (중복 방지)
        pass
    env_logger.info("=== 환경 체크 시작 ===")
    try:
        os_name = platform.system()
        os_version = platform.version()
        arch = platform.architecture()[0]
        env_logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")
        env_logger.info(f"Python Version: {sys.version}")
        missing_libs = []
        for lib in ["pandas", "numpy", "FinanceDataReader", "matplotlib", "sklearn"]:
            try:
                __import__(lib)
            except Exception:
                missing_libs.append(lib)
        if missing_libs:
            env_logger.warning(f"설치되지 않은 라이브러리: {', '.join(missing_libs)}")
        else:
            env_logger.info("필수 라이브러리 모두 설치됨")
    except Exception as e:
        env_logger.warning(f"환경체크 중 오류: {e}")
    env_logger.info("=== 환경 체크 완료 ===\n")

# 호출
check_environment()

# -----------------------
# Helper: krx list loader
# -----------------------
def load_krx_list(krx_json_path=default_krx_json, use_local_only=True):
    """
    로컬 JSON이 있으면 우선 사용.
    없고 use_local_only==False 이면 FinanceDataReader로 시도(환경 허용 시).
    반환: pandas.DataFrame (빈 DF이면 실패)
    """
    # 1) 로컬 JSON 우선
    if os.path.exists(krx_json_path):
        try:
            logger.info(f"로컬 KRX JSON 로드: {krx_json_path}")
            df = pd.read_json(krx_json_path, orient='records', dtype=False)
            return df
        except Exception as e:
            logger.error(f"로컬 KRX JSON 로드 실패: {e}")

    # 2) 로컬 전용 모드라면 실패 반환
    if use_local_only:
        logger.warning("use_local_only=True 이고 로컬 KRX JSON을 찾을 수 없어 KRX 목록 로드 불가.")
        return pd.DataFrame()

    # 3) FinanceDataReader 시도 (네트워크 허용 시)
    if not FDR_AVAILABLE:
        logger.error("FinanceDataReader 모듈이 없음. --update_data 옵션을 사용하려면 FDR 설치 필요.")
        return pd.DataFrame()
    try:
        logger.info("FinanceDataReader로 KRX 목록 다운로드 시도...")
        krx_list = fdr.StockListing('KRX')
        # 표준 컬럼 정리
        cols = krx_list.columns.tolist()
        # 유니폼한 컬럼 처리(이름 매핑)
        # 가능한 공통 컬럼: 'Symbol' or 'Code' for code, 'Name' for name
        return krx_list
    except Exception as e:
        logger.error(f"FinanceDataReader로 KRX 목록 로드 실패: {e}")
        return pd.DataFrame()

# -----------------------
# Optional: FinanceDataReader wrapper (update mode)
# -----------------------
@backoff.on_exception(backoff.expo, RemoteDisconnected, max_tries=5, jitter=backoff.full_jitter)
def fetch_fdr_with_retry(symbol, start=None, end=None):
    return fdr.DataReader(symbol, start=start, end=end)

def fetch_fdr_and_save(symbol):
    file_path = os.path.join(data_dir, f"{symbol}.parquet")
    today = datetime.now().strftime('%Y-%m-%d')
    try:
        if os.path.exists(file_path):
            existing_df = pd.read_parquet(file_path)
            last_date = existing_df.index.max().strftime('%Y-%m-%d')
            new_data = fetch_fdr_with_retry(symbol, start=last_date, end=today)
            if new_data is None or new_data.empty:
                logger.info(f"{symbol} : 신규 데이터 없음")
                return
            # concat만 새 날짜 부분
            updated_df = pd.concat([existing_df, new_data[new_data.index > existing_df.index.max()]])
            updated_df.to_parquet(file_path)
            logger.info(f"{symbol} 데이터 업데이트 완료")
        else:
            df = fetch_fdr_with_retry(symbol)
            if df is None or df.empty:
                logger.warning(f"{symbol} : 수신된 데이터 없음")
                return
            df.to_parquet(file_path)
            logger.info(f"{symbol} 전체 데이터 새로 저장")
    except Exception as e:
        logger.error(f"{symbol} 데이터 처리 실패: {e}")

def save_all_data_from_fdr(krx_df, max_workers=5):
    """
    FinanceDataReader를 사용하여 모든 종목을 (병렬로) 갱신.
    krx_df : KRX DataFrame (must contain code column)
    """
    if krx_df.empty:
        logger.error("KRX 목록이 없습니다. 업데이트 불가.")
        return

    # 가능한 종목코드 컬럼 찾기
    symbol_col = next((c for c in ['Symbol', 'Code', '종목코드', 'code'] if c in krx_df.columns), None)
    if not symbol_col:
        logger.error("KRX 목록에 종목코드 컬럼이 없습니다.")
        return

    symbols = krx_df[symbol_col].astype(str).tolist()
    logger.info(f"업데이트 대상 종목 수: {len(symbols)}")

    with ThreadPoolExecutor(max_workers=max_workers) as ex:
        futures = {ex.submit(fetch_fdr_and_save, sym): sym for sym in symbols}
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                logger.error(f"업데이트 중 오류 ({futures[future]}): {e}")

# -----------------------
# Plot helper (base64)
# -----------------------
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

# -----------------------
# Core: find similar using local data
# -----------------------
def find_similar_chart_local(base_symbol, start_date, end_date, krx_df, n_similar_stocks=5):
    """
    로컬 parquet 파일들만 사용하여 유사 종목 탐색
    krx_df: KRX DataFrame (must contain code and name columns)
    """
    logger.info(f"분석 시작 - 기준 종목: {base_symbol}, 시작일: {start_date}, 종료일: {end_date}")

    # 종목 코드/이름 컬럼 자동 탐색
    symbol_col = next((c for c in ['Symbol', 'Code', '종목코드', 'code'] if c in krx_df.columns), None)
    name_col = next((c for c in ['Name', '회사명', 'name'] if c in krx_df.columns), None)
    if not symbol_col:
        logger.error("KRX 목록에 종목코드 컬럼이 없어 로컬 분석 불가.")
        return None

    krx_symbols = krx_df[symbol_col].astype(str).tolist()
    krx_name_map = {}
    if name_col:
        try:
            krx_name_map = krx_df.set_index(symbol_col)[name_col].to_dict()
        except Exception:
            krx_name_map = {}

    # 기준 데이터 로드
    base_fp = os.path.join(data_dir, f"{base_symbol}.parquet")
    if not os.path.exists(base_fp):
        logger.error(f"기준 종목({base_symbol})의 데이터 파일이 존재하지 않습니다: {base_fp}")
        return None

    try:
        base_data = pd.read_parquet(base_fp)
        # 인덱스를 datetime으로 보장
        if not np.issubdtype(base_data.index.dtype, np.datetime64):
            base_data.index = pd.to_datetime(base_data.index)
        base_data = base_data.loc[start_date:end_date]
    except Exception as e:
        logger.error(f"기준 종목 데이터 로드 실패 ({base_symbol}): {e}")
        return None

    if base_data.empty:
        logger.error("기준 종목 데이터가 비어있음.")
        return None

    base_close_prices = base_data['Close']
    similarities = []

    def process_stock(symbol):
        if str(symbol) == str(base_symbol):
            return None
        try:
            fp = os.path.join(data_dir, f"{symbol}.parquet")
            if not os.path.exists(fp):
                return None
            df = pd.read_parquet(fp)
            if not np.issubdtype(df.index.dtype, np.datetime64):
                df.index = pd.to_datetime(df.index)
            close_prices = df['Close'].loc[start_date:end_date]
            # reindex to base index, linear interpolate
            close_prices = close_prices.reindex(base_close_prices.index).interpolate(method='linear')
            if close_prices.isnull().any():
                # 누락이 있으면 제외
                logger.debug(f"데이터 불충분: {symbol}")
                return None
            # normalization (z-score)
            bp = (base_close_prices - np.mean(base_close_prices)) / (np.std(base_close_prices) or 1.0)
            sp = (close_prices - np.mean(close_prices)) / (np.std(close_prices) or 1.0)
            cos_sim = cosine_similarity(bp.values.reshape(1, -1), sp.values.reshape(1, -1))
            return {
                "ticker": symbol,
                "name": krx_name_map.get(symbol, '알 수 없음'),
                "cosine_similarity": float(cos_sim.item())
            }
        except Exception as e:
            logger.debug(f"처리 실패 {symbol}: {e}")
            return None

    with ThreadPoolExecutor(max_workers=5) as ex:
        futures = {ex.submit(process_stock, s): s for s in krx_symbols}
        for future in as_completed(futures):
            try:
                res = future.result()
                if res:
                    similarities.append(res)
            except Exception as e:
                logger.debug(f"유사도 계산 중 에러 ({futures[future]}): {e}")

    if not similarities:
        logger.info("유사한 종목을 찾지 못했습니다.")
        return []

    similarities.sort(key=lambda x: x['cosine_similarity'], reverse=True)
    top_n = similarities[:n_similar_stocks]
    logger.info(f"분석 완료 - 상위 {len(top_n)}개 종목 결과")
    return top_n

def plot_single_chart_local(base_symbol, compare_symbol, start_date, end_date):
    chart_data_list = []
    try:
        base_fp = os.path.join(data_dir, f"{base_symbol}.parquet")
        base_df = pd.read_parquet(base_fp)
        if not np.issubdtype(base_df.index.dtype, np.datetime64):
            base_df.index = pd.to_datetime(base_df.index)
        base_df = base_df.loc[start_date:end_date]
        chart_data_list.append({'data': base_df, 'label': f"{base_symbol} (기준)", 'linewidth': 2.5})
    except Exception as e:
        logger.error(f"차트용 데이터 로드 실패: {base_symbol} - {e}")
        return None

    try:
        cmp_fp = os.path.join(data_dir, f"{compare_symbol}.parquet")
        cmp_df = pd.read_parquet(cmp_fp)
        if not np.issubdtype(cmp_df.index.dtype, np.datetime64):
            cmp_df.index = pd.to_datetime(cmp_df.index)
        cmp_df = cmp_df.loc[start_date:end_date]
        chart_data_list.append({'data': cmp_df, 'label': f"{compare_symbol} (비교)", 'linewidth': 1.5})
    except Exception as e:
        logger.error(f"차트용 데이터 로드 실패: {compare_symbol} - {e}")
        return None

    if any(item['data'].empty for item in chart_data_list):
        logger.error("차트 데이터를 생성할 수 없습니다. 데이터 기간을 확인하세요.")
        return None

    title = f"{base_symbol} vs {compare_symbol}"
    return plot_and_get_base64(chart_data_list, start_date, end_date, title)

# -----------------------
# CLI / main
# -----------------------
def main():
    parser = argparse.ArgumentParser(description="주가 차트 유사성 분석 (로컬 기반 우선)")
    parser.add_argument("--base_symbol", "-b", type=str, required=True, help="비교 기준 종목 코드 (6자리)")
    parser.add_argument("--start_date", "-s", type=str, default="2023-01-01", help="시작일 YYYY-MM-DD")
    parser.add_argument("--end_date", "-e", type=str, default=datetime.now().strftime('%Y-%m-%d'), help="종료일 YYYY-MM-DD")
    parser.add_argument("--compare_symbol", type=str, help="개별 비교 종목 (이 옵션 사용 시 유사도 검색 대신 차트 출력)")
    parser.add_argument("--n_similar", type=int, default=5, help="유사 종목 수")
    parser.add_argument("--krx_json", type=str, default=default_krx_json, help="로컬 KRX JSON 경로")
    parser.add_argument("--use_local_only", action="store_true", help="로컬 데이터만 사용하고 외부 호출 금지")
    parser.add_argument("--update_data", action="store_true", help="(선택) FDR 사용하여 로컬 parquet 데이터 갱신 시도 (네트워크 허용 시)")
    parser.add_argument("--max_workers", type=int, default=5, help="병렬 작업자 수 (업데이트 시 적용)")
    args = parser.parse_args()

    # 날짜 포맷 보정
    start_date = args.start_date
    end_date = args.end_date

    # KRX 목록 로드
    krx_df = load_krx_list(krx_json_path=args.krx_json, use_local_only=args.use_local_only)
    if krx_df.empty:
        logger.warning("KRX 목록을 불러오지 못했습니다. --use_local_only 여부와 krx JSON 경로를 확인하세요.")
        # 계속 진행하되 find_similar_chart_local에서는 에러날 수 있음

    # update_data 요청 처리 (명시적)
    if args.update_data:
        if args.use_local_only:
            logger.warning("--use_local_only 옵션이 켜져 있어 업데이트를 실행하지 않습니다.")
        else:
            if not FDR_AVAILABLE:
                logger.error("FinanceDataReader 설치되어 있지 않아 업데이트 불가.")
            else:
                # krx_df 를 fdr로 다시 로드(네트워크), 또는 기존 krx_df가 비어있으면 시도
                if krx_df.empty:
                    krx_df = load_krx_list(krx_json_path=args.krx_json, use_local_only=False)
                if not krx_df.empty:
                    # Standardize symbol column name if necessary
                    symbol_col = next((c for c in ['Symbol', 'Code', '종목코드', 'code'] if c in krx_df.columns), None)
                    if symbol_col and symbol_col != 'code':
                        # ensure 'code' column for internal operation (string padded)
                        try:
                            krx_df['code'] = krx_df[symbol_col].astype(str).apply(lambda x: x.zfill(6))
                        except Exception:
                            krx_df['code'] = krx_df[symbol_col].astype(str)
                        # Save back to local JSON for subsequent offline runs
                        try:
                            krx_df.to_json(args.krx_json, orient='records', force_ascii=False, indent=4)
                            logger.info(f"KRX JSON 저장: {args.krx_json}")
                        except Exception as e:
                            logger.warning(f"KRX JSON 저장 실패: {e}")
                    # Launch update
                    save_all_data_from_fdr(krx_df, max_workers=args.max_workers)

    # Main flow: either plot single compare or find similar
    try:
        if args.compare_symbol:
            img_b64 = plot_single_chart_local(args.base_symbol, args.compare_symbol, start_date, end_date)
            if img_b64:
                print(json.dumps({"image_data": img_b64}))
            else:
                print(json.dumps({"error": "차트 이미지를 생성할 수 없습니다."}, ensure_ascii=False))
        else:
            similar_stocks = find_similar_chart_local(args.base_symbol, start_date, end_date, krx_df, n_similar_stocks=args.n_similar)
            if similar_stocks is None:
                print(json.dumps({"error": "유사한 종목을 찾을 수 없습니다. (기준 데이터 확인 필요)"}, ensure_ascii=False))
            else:
                out = {"base_symbol": args.base_symbol, "similar_stocks": similar_stocks}
                print(json.dumps(out, ensure_ascii=False, indent=2))
    except Exception as e:
        logger.error(f"스크립트 실행 중 치명적 오류 발생: {e}\n{traceback.format_exc()}")
        print(json.dumps({"error": f"스크립트 실행 중 오류 발생: {str(e)}"}, ensure_ascii=False))

if __name__ == "__main__":
    main()
