import requests
import pandas as pd
import sys
import io
import os

# 콘솔 한글 깨짐 방지 (Windows)
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def fetch_krx():
    """
    KRX 상장법인 전체 종목 조회
    """
    url = "http://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13"
    try:
        response = requests.get(url)
        response.raise_for_status()
        df = pd.read_html(response.content, encoding='CP949')[0]

        # 필요한 컬럼 선택 및 컬럼명 최신화
        df = df[['회사명', '시장구분', '종목코드', '업종', '주요제품', '상장일',
                 '결산월', '대표자명', '홈페이지', '지역']]
        df.columns = ['name', 'market', 'code', 'sector', 'product',
                      'listedDate', 'settleMonth', 'ceo', 'website', 'region']

        # 종목코드 6자리로 통일
        df['code'] = df['code'].apply(lambda x: str(x).zfill(6))

        return df

    except Exception as e:
        print(f"KRX 데이터 fetch 실패: {e}")
        return pd.DataFrame()

def save_json(df, file_path=None):
    """
    DataFrame을 JSON 파일로 저장
    """
    if df.empty:
        print("KRX 전체 데이터가 비어 있습니다.")
        return

    if file_path is None:
        file_path = os.path.join(os.path.dirname(__file__), "krx_list_full.json")

    try:
        df.to_json(file_path, orient='records', force_ascii=False, indent=4)
        print(f"{file_path} 파일 생성 완료")
    except Exception as e:
        print(f"JSON 저장 실패: {e}")

if __name__ == "__main__":
    print("KRX 데이터 fetch 시작...")
    df_krx = fetch_krx()
    print(f"KRX 데이터 수: {len(df_krx)}")

    save_json(df_krx)