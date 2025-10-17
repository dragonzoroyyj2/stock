import requests
import pandas as pd
import urllib3
import os
import sys

# 콘솔 UTF-8 강제 (STS/Windows 터미널 모두 안전)
try:
    sys.stdout.reconfigure(encoding='utf-8')
except AttributeError:
    pass  # Python 3.6 이하에서는 무시

# SSL 경고 비활성화
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# JSON 파일 절대경로
JSON_FILE = r"C:\LocBootProject\workspace\MyBaseLink\python\krx_list_full.json"

def fetch_krx():
    url = "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13"
    try:
        # SSL 인증 무시
        response = requests.get(url, verify=False, timeout=10)
        response.raise_for_status()

        # HTML 테이블 읽기
        df = pd.read_html(response.content, encoding='CP949')[0]

        # 필요한 컬럼만 추출 및 컬럼명 영문 변경
        df = df[['회사명', '시장구분', '종목코드', '업종', '주요제품', '상장일', '결산월', '대표자명', '홈페이지', '지역']]
        df.columns = [
            'name', 'market', 'code', 'sector', 'product',
            'listedDate', 'settleMonth', 'ceo', 'website', 'region'
        ]

        # 종목코드 6자리 통일
        df['code'] = df['code'].apply(lambda x: str(x).zfill(6))

        print(f"KRX 데이터 fetch 성공 ({len(df)}건)")
        return df

    except Exception as e:
        print(f"KRX 데이터 fetch 실패: {e}")
        return pd.DataFrame()

def save_json(df):
    if df.empty:
        print("KRX 전체 데이터가 비어 있습니다. 저장 생략.")
        return

    try:
        # 폴더 없으면 자동 생성
        os.makedirs(os.path.dirname(JSON_FILE), exist_ok=True)
        df.to_json(JSON_FILE, orient='records', force_ascii=False, indent=4)
        print(f"JSON 파일 생성 완료: {JSON_FILE}")

    except Exception as e:
        print(f"JSON 저장 실패: {e}")

if __name__ == "__main__":
    df_krx = fetch_krx()
    print(f"KRX 데이터 수: {len(df_krx)}")
    save_json(df_krx)
