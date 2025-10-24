# C:\LocBootProject\workspace\MyBaseLink\python\find_chart_patterns.py

import os
import sys
import argparse
import logging
import json
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
import numpy as np
from scipy.signal import find_peaks

# 공통 유틸리티 모듈 임포트
from common_utils import (
    logger,
    check_environment,
    fetch_fdr_with_retry_with_cache,
    set_korean_font,
    generate_chart_with_cache,
    data_dir,
    get_stock_listing_from_cache,
    flush_log
)

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
    
    peaks_indices, _ = find_peaks(df_last_60['Close'], distance=10, prominence=df_last_60['Close'].max() * 0.02)
    
    if len(peaks_indices) < 3:
        return False

    peaks_values = df_last_60['Close'].iloc[peaks_indices]
    sorted_peaks_by_height = peaks_values.sort_values(ascending=False)
    
    if len(sorted_peaks_by_height) < 3:
        return False

    head_peak_idx = sorted_peaks_by_height.index.values[0]
    
    left_shoulder_candidates = [idx for idx in peaks_indices if idx < head_peak_idx]
    right_shoulder_candidates = [idx for idx in peaks_indices if idx > head_peak_idx]
    
    if not left_shoulder_candidates or not right_shoulder_candidates:
        return False
        
    left_shoulder_idx = max(left_shoulder_candidates)
    right_shoulder_idx = min(right_shoulder_candidates)

    head_height = df_last_60.loc[head_peak_idx]['Close']
    left_shoulder_height = df_last_60.loc[left_shoulder_idx]['Close']
    right_shoulder_height = df_last_60.loc[right_shoulder_idx]['Close']

    if not (head_height > left_shoulder_height and head_height > right_shoulder_height):
        return False
        
    shoulders_avg = (left_shoulder_height + right_shoulder_height) / 2
    if shoulders_avg == 0:
        return False
    shoulders_diff_ratio = abs(left_shoulder_height - right_shoulder_height) / shoulders_avg
    if shoulders_diff_ratio > 0.15:
        return False
    
    head_vs_shoulders_ratio = (head_height - shoulders_avg) / shoulders_avg
    if head_vs_shoulders_ratio < 0.05:
        return False

    return True

# ========================================================================
# 메인 로직
# ========================================================================

def process_symbol_for_pattern(symbol_info, days):
    """단일 종목에 대한 패턴 감지 및 결과 반환을 처리합니다."""
    symbol, name = symbol_info
    end_date = datetime.now()
    start_date = end_date - timedelta(days=days)
    
    # fetch_fdr_with_retry_with_cache 함수에서 SystemExit 예외를 발생시킴
    df = fetch_fdr_with_retry_with_cache(symbol, start=start_date.strftime('%Y-%m-%d'), end=end_date.strftime('%Y-%m-%d'))
    
    if df is None or df.empty:
        return None
    
    if detect_head_and_shoulders(df):
        chart_base64 = generate_chart_with_cache(symbol, name, df, start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d'))
        return {
            "symbol": symbol,
            "name": name,
            "pattern": "Head and Shoulders",
            "chart": chart_base64
        }
    return None

def process_symbol_for_chart(symbol, start_date_str, end_date_str):
    """단일 종목에 대한 차트를 생성하고 반환합니다."""
    try:
        datetime.strptime(start_date_str, '%Y-%m-%d')
        datetime.strptime(end_date_str, '%Y-%m-%d')
    except ValueError:
        error_msg = "잘못된 날짜 형식입니다. YYYY-MM-DD 형식으로 입력해주세요."
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        return None

    # fetch_fdr_with_retry_with_cache 함수에서 SystemExit 예외를 발생시킴
    df = fetch_fdr_with_retry_with_cache(symbol, start=start_date_str, end=end_date_str)
    
    if df is None or df.empty:
        error_msg = "데이터를 가져올 수 없습니다."
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        return None
    
    stock_listing = get_stock_listing_from_cache()
    stock_name = symbol
    if stock_listing is not None:
        name_series = stock_listing.loc[stock_listing['code'] == symbol, 'name']
        if not name_series.empty:
            stock_name = name_series.iloc[0]

    chart_base64 = generate_chart_with_cache(symbol, stock_name, df, start_date_str, end_date_str)
    if chart_base64 is None:
        error_msg = "차트 생성 실패"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        return None
        
    return {"image_data": chart_base64}


def find_patterns_in_stocks(num_processes, days, pattern_name, topN):
    """전체 종목에 대해 멀티프로세싱을 사용하여 패턴을 감지합니다."""
    krx = get_stock_listing_from_cache()
    if krx is None:
        print(json.dumps({"error": "종목 목록 파일 로드 실패"}), file=sys.stderr)
        sys.exit(1)
        
    symbols = [(row['code'], row['name']) for _, row in krx.iterrows()]
    
    results = []
    
    if num_processes > 1:
        pool = Pool(processes=num_processes)
        try:
            pool_results = pool.starmap(process_symbol_for_pattern, [(s, days) for s in symbols])
            pool.close()
            pool.join()
        except Exception as e:
            logger.error(f"멀티프로세싱 작업 중 치명적인 오류 발생: {e}")
            pool.terminate()
            pool.join()
            print(json.dumps({"error": str(e)}), file=sys.stderr)
            sys.exit(1)
        
        for res in pool_results:
            if res:
                results.append(res)
    else:
        try:
            pool_results = [process_symbol_for_pattern(s, days) for s in symbols]
            for res in pool_results:
                if res:
                    results.append(res)
        except SystemExit as e:
            print(json.dumps({"error": str(e)}), file=sys.stderr)
            sys.exit(1)


    if topN and topN > 0:
        results = results[:topN]
        
    return results

def main():
    parser = argparse.ArgumentParser(description="주식 차트 패턴을 감지하고 차트를 생성하는 스크립트")
    parser.add_argument("--base_symbol", type=str, help="단일 종목 차트 조회용 심볼")
    parser.add_argument("--start_date", type=str, help="분석 시작일 (YYYY-MM-DD)")
    parser.add_argument("--end_date", type=str, help="분석 종료일 (YYYY-MM-DD)")
    parser.add_argument("--pattern", type=str, help="검색할 차트 패턴")
    parser.add_argument("--days", type=int, help="분석할 최근 일수")
    parser.add_argument("--topN", type=int, help="상위 N개 결과")
    parser.add_argument("--workers", type=int, default=cpu_count(), help="멀티프로세싱 워커 수")

    args = parser.parse_args()

    check_environment(["pandas", "FinanceDataReader", "matplotlib", "requests", "numpy", "scipy"])
    
    if args.base_symbol:
        logger.info(f"단일 종목 차트 요청: {args.base_symbol} ({args.start_date} ~ {args.end_date})")
        result = process_symbol_for_chart(args.base_symbol, args.start_date, args.end_date)
        if result:
            print(json.dumps(result, ensure_ascii=False))
        
    elif args.pattern:
        days_to_analyze = args.days if args.days else 120
        logger.info(f"차트 패턴 감지 시작 (패턴: {args.pattern}, 분석일수: {days_to_analyze}, 워커 수: {args.workers})")
        
        start_time = time.time()
        patterns = find_patterns_in_stocks(args.workers, days_to_analyze, args.pattern, args.topN)
        end_time = time.time()
        
        logger.info(f"총 {len(patterns)}개의 패턴 감지. 총 소요 시간: {end_time - start_time:.2f}초")
            
        print(json.dumps(patterns, ensure_ascii=False, indent=4))
    
    else:
        error_msg = "인자가 부족합니다. --base_symbol 또는 --pattern을 지정해야 합니다."
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        sys.exit(1)

if __name__ == "__main__":
    freeze_support()
    main()
