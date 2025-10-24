# C:\LocBootProject\workspace\MyBaseLink\python\update_stock_listing.py

import os
import sys
import pickle
import FinanceDataReader as fdr
from datetime import datetime
import json
import pandas as pd

# 공통 유틸리티 모듈 임포트
from common_utils import (
    logger, 
    data_dir, 
    check_environment, 
    flush_log, 
    ssl, 
    urllib3
)
from http.client import RemoteDisconnected
from urllib3.exceptions import MaxRetryError, SSLError as Urllib3SSLError
from requests.exceptions import ConnectionError, SSLError as RequestsSSLError

def get_stock_listing(data_dir):
    """KRX 상장 종목 목록을 가져와 저장합니다."""
    cache_path_pkl = os.path.join(data_dir, "stock_listing.pkl")
    cache_path_json = os.path.join(data_dir, "stock_listing.json")
    
    try:
        logger.info("KRX 종목 목록 조회 시작")
        krx = fdr.StockListing('KRX')

        # 'Code' 열이 존재하면 'code'로 변경
        if 'Code' in krx.columns:
            krx.rename(columns={'Code': 'code'}, inplace=True)
        # 'Name' 열이 존재하면 'name'으로 변경
        if 'Name' in krx.columns:
            krx.rename(columns={'Name': 'name'}, inplace=True)

        logger.info(f"열 이름 변경 완료. 최종 열: {krx.columns.tolist()}")
        
        # JSON 파일로 저장
        krx.to_json(cache_path_json, orient='records', force_ascii=False)
        logger.info(f"KRX 종목 목록을 {cache_path_json}에 저장 완료.")

        # pickle 파일로도 저장 (파이썬 내부용)
        with open(cache_path_pkl, 'wb') as f:
            pickle.dump(krx, f)
        logger.info(f"KRX 종목 목록을 {cache_path_pkl}에 저장 완료.")

        return krx
    except (RemoteDisconnected, ssl.SSLError, Urllib3SSLError, MaxRetryError, ConnectionError, RequestsSSLError) as e:
        error_msg = f"KRX 종목 목록 조회 중 통신(네트워크/SSL) 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        sys.exit(1)
    except Exception as e:
        error_msg = f"KRX 종목 목록 조회 중 일반 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        sys.exit(1)

def main():
    check_environment(["pandas", "FinanceDataReader"])
    
    logger.info("전체 종목 목록 업데이트 시작")
    krx = get_stock_listing(data_dir)
    if krx is not None and not krx.empty:
        logger.info(f"전체 종목 목록 업데이트 완료 (총 {len(krx)}건)")
        print(json.dumps({"status": "success", "message": "전체 종목 목록이 성공적으로 업데이트되었습니다.", "count": len(krx)}))
    else:
        logger.error("전체 종목 목록 업데이트 실패")
        print(json.dumps({"error": "전체 종목 목록 업데이트 실패."}), file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
