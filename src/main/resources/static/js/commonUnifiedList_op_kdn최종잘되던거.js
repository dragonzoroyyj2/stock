/**
 * 🧩 commonUnifiedList_op.js (클래스 기반으로 리팩토링 및 이벤트 위임 적용)
 * --------------------------------------------------------
 * ✅ 공용 리스트/CRUD/엑셀 + 반응형 테이블 + 페이징 자동조정
 * --------------------------------------------------------
 *
 * 사용법:
 *   const unifiedListManager = initUnifiedList({ mode: "server" | "client", ...config });
 *
 * 주요 기능:
 *   1. 리스트 조회 및 페이징 (서버 / 클라이언트 모드 지원)
 *   2. 검색 기능 (엔터/버튼)
 *   3. 체크박스 전체 선택/해제
 *   4. 모달 등록/상세/수정
 *   5. 선택 삭제
 *   6. 엑셀 다운로드
 *   7. 화면 리사이즈에 따른 페이징 버튼 조정 (commonPagination_op.js 사용)
 *   8. 화면별로 버튼 가시성 제어 (config.buttons 사용)
 *   9. 환경에 따른 검색 로직 분기 (loadList(page, env) 사용)
 *
 * ⚠️ 이 파일은 commonPagination_op.js가 로드된 후 로드되어야 합니다.
 */

function initUnifiedList(config) {
  return new UnifiedList(config);
}

class UnifiedList {
  constructor(config) {
    this.config = config;
    this.currentPage = 0;
    this.pageSize = config.pageSize || 10;
    this.pageGroupSize = config.pageGroupSize || 5;
    this.isFullDataLoaded = false;
    this.fullDataCache = [];
    this.totalPagesCache = 0;
    this.$ = sel => document.querySelector(sel);
    this.$$ = sel => document.querySelectorAll(sel);
    this.csrfToken = document.querySelector("meta[name='_csrf']")?.content;
    this.csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;

    this.config.addBtnSelector = this.config.addBtnSelector || null;
    this.config.saveBtnSelector = this.config.saveBtnSelector || null;
    this.config.deleteSelectedBtnSelector = this.config.deleteSelectedBtnSelector || null;
    this.config.updateBtnSelector = this.config.updateBtnSelector || null;
    this.config.excelBtnSelector = this.config.excelBtnSelector || null;
    this.config.checkAllSelector = this.config.checkAllSelector || '#checkAll';
    this.config.modalId = this.config.modalId || '#addModal';
    this.config.detailModalId = this.config.detailModalId || '#detailModal';

    // 추가 요청사항을 위한 설정 변수 추가
    this.config.enableDetailView = config.enableDetailView ?? true;
    this.config.enableRowClickDetail = config.enableRowClickDetail ?? false;
    this.config.detailViewButtons = config.detailViewButtons || {};
    
    this.init();
  }

  init() {
    this.toggleButtons();
    this.bindEvents();
    this.loadList(0);
  }

  // ===============================
  // 데이터 로드
  // ===============================
  async loadList(page = 0, environment = 'web', search = '') {
    this.currentPage = page;

    if (this.config.mode === "server") {
      try {
        const params = new URLSearchParams();
        params.append('page', page);
        params.append('size', this.pageSize);
        params.append('search', search);
        params.append('mode', this.config.mode);
        params.append('pagination', this.config.pagination);
        params.append('env', environment);

        const url = `${this.config.apiUrl}?${params.toString()}`;

        const res = await fetch(url, this.fetchOptions("GET"));
        if (!res.ok) throw new Error("데이터 조회 실패");
        const data = await res.json();
        const content = data.content || [];
        this.renderTable(content);
        const totalCountEl = this.$("#totalCount");
        if (totalCountEl) totalCountEl.textContent = `총 ${data.totalElements ?? content.length}건`;

        const pagingEl = this.$(this.config.paginationSelector);
        if (this.config.pagination && pagingEl) {
          this.totalPagesCache = data.totalPages ?? Math.ceil((data.totalElements ?? content.length) / this.pageSize);
          renderPagination(this.currentPage, this.totalPagesCache, this.config.paginationSelector, this.loadList.bind(this), this.pageGroupSize);
        } else if (pagingEl) {
          pagingEl.innerHTML = "";
        }
      } catch (err) {
        console.error(err);
        alert("데이터 조회 중 오류 발생");
      }
    } else if (this.config.mode === "client") {
      try {
        if (!this.isFullDataLoaded) {
          const res = await fetch(`${this.config.apiUrl}?mode=client`, this.fetchOptions("GET"));
          if (!res.ok) throw new Error("전체 데이터 조회 실패");
          const json = await res.json();
          this.fullDataCache = Array.isArray(json.content) ? json.content : [];
          this.isFullDataLoaded = true;
        }

        const searchLower = search.toLowerCase();
        let filtered = search
          ? this.fullDataCache.filter(item =>
              Object.values(item).some(v => String(v ?? "").toLowerCase().includes(searchLower))
            )
          : this.fullDataCache;

        const pageData = this.config.pagination ? filtered.slice(page * this.pageSize, (page + 1) * this.pageSize) : filtered;
        this.renderTable(pageData);

        const totalCountEl = this.$("#totalCount");
        if (totalCountEl) totalCountEl.textContent = `총 ${filtered.length}건`;

        const pagingEl = this.$(this.config.paginationSelector);
        if (this.config.pagination && pagingEl) {
          this.totalPagesCache = Math.max(1, Math.ceil(filtered.length / this.pageSize));
          renderPagination(this.currentPage, this.totalPagesCache, this.config.paginationSelector, this.loadList.bind(this), this.pageGroupSize);
        } else if (pagingEl) {
          pagingEl.innerHTML = "";
        }
      } catch (err) {
        console.error(err);
        alert("데이터 로드 실패 (client mode)");
      }
    }
  }

  // ===============================
  // 테이블 렌더링
  // ===============================
  renderTable(list) {
    const tbody = this.$(this.config.tableBodySelector);
    if (!tbody) return;
    tbody.innerHTML = "";

    if (!Array.isArray(list) || list.length === 0) {
      const colSpan = this.config.columns.length + 1; // 체크박스 열 포함
      tbody.innerHTML = `<tr><td colspan="${colSpan}">데이터가 없습니다.</td></tr>`;
      return;
    }

    list.forEach(row => {
      const tr = document.createElement("tr");
      const chkTd = document.createElement("td");
      chkTd.innerHTML = `<input type="checkbox" value="${row.id}">`;
      tr.appendChild(chkTd);

      this.config.columns.forEach(col => {
        const td = document.createElement("td");
        const val = row[col.key] ?? "";
        
        if (col.isDetailLink && this.config.enableDetailView && !this.config.enableRowClickDetail) {
          td.innerHTML = `<a href="#" data-id="${row.id}" class="detail-link">${val}</a>`;
        } else {
          td.textContent = val;
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });

    const checkAllEl = this.$(this.config.checkAllSelector);
    if (checkAllEl) checkAllEl.checked = false;
  }

  // ===============================
  // 버튼 가시성 제어
  // ===============================
  toggleButtons() {
    const buttonMap = {
      search: this.config.searchBtnSelector,
      searchInput: this.config.searchInputSelector,
      add: this.config.addBtnSelector,
      save: this.config.saveBtnSelector,
      deleteSelected: this.config.deleteSelectedBtnSelector,
      update: this.config.updateBtnSelector,
      excel: this.config.excelBtnSelector
    };

    const buttonsConfig = this.config.buttons || {};

    Object.keys(buttonMap).forEach(key => {
      const selector = buttonMap[key];
      const element = this.$(selector);
      if (element) {
        if (buttonsConfig.hasOwnProperty(key) && buttonsConfig[key] === false) {
          element.style.display = 'none';
        } else {
          element.style.display = '';
        }
      }
    });
  }

  // ===============================
  // 이벤트 바인딩 (이벤트 위임 적용)
  // ===============================
  bindEvents() {
    document.body.addEventListener('click', e => {
      const target = e.target;

      // 검색 버튼 클릭
      if (target.matches(this.config.searchBtnSelector)) {
        e.preventDefault();
        const environment = window.innerWidth < 768 ? 'mobile' : 'web';
        const searchValue = this.$(this.config.searchInputSelector)?.value || '';
        this.loadList(0, environment, searchValue);
        return;
      }

      // 추가 버튼 클릭 (closest를 사용하여 부모 요소까지 확인)
      const addBtn = target.closest(this.config.addBtnSelector);
      if (addBtn) {
        e.preventDefault();
        this.openAddModal();
        return;
      }

      // 저장 버튼 클릭
      const saveBtn = target.closest(this.config.saveBtnSelector);
      if (saveBtn) {
        e.preventDefault();
        this.saveItem();
        return;
      }
      
      // 선택 삭제 버튼 클릭
      if (target.matches(this.config.deleteSelectedBtnSelector)) {
        e.preventDefault();
        this.deleteSelectedItems();
        return;
      }

      // 엑셀 버튼 클릭
      if (target.matches(this.config.excelBtnSelector)) {
        e.preventDefault();
        this.downloadExcel();
        return;
      }
      
      // 행 전체 클릭 시 상세 보기 (enableRowClickDetail이 true일 때만)
      if (this.config.enableRowClickDetail && this.config.enableDetailView) {
        const row = target.closest(`${this.config.tableBodySelector} tr`);
        if (row && !target.closest('input[type="checkbox"]')) {
          const id = row.querySelector('input[type="checkbox"]')?.value;
          if (id) {
            e.preventDefault();
            this.openDetailModal(id);
            return;
          }
        }
      }

      // 상세 링크 클릭 (enableRowClickDetail이 false일 때만)
      const detailLink = target.closest('.detail-link');
      if (detailLink && this.config.enableDetailView && !this.config.enableRowClickDetail) {
        e.preventDefault();
        const id = detailLink.dataset.id;
        this.openDetailModal(id);
        return;
      }

      // 모달 내 버튼 제어
      const modalElement = target.closest('.modal');
      if (modalElement) {
        // 모달 닫기 버튼 클릭
        const closeBtn = target.closest('.close-btn, [data-close]');
        if (closeBtn) {
          e.preventDefault();
          const modalId = closeBtn.dataset.close || modalElement.id;
          this.closeModal(modalId);
          return;
        }

        // 상세 모달 내 버튼 제어
        if (modalElement.id === this.config.detailModalId.replace('#', '')) {
          // 수정 버튼 클릭
          const updateBtn = target.closest(this.config.updateBtnSelector);
          if (updateBtn && this.config.detailViewButtons.update) {
            e.preventDefault();
            this.updateItem();
            return;
          }
          
          // 상세 모달 내 삭제 버튼 클릭
          const deleteBtn = target.closest(this.config.detailViewButtons.deleteBtnSelector);
          if (deleteBtn && this.config.detailViewButtons.delete) {
              e.preventDefault();
              const id = this.$('#detailId')?.value;
              if (id) {
                this.deleteItem(id);
              }
              return;
          }
        }
      }

      // 모달 외부 클릭 시 닫기
      if (target.matches('.modal')) {
        this.closeModal(target.id);
        return;
      }

      // 전체 체크박스 클릭
      if (target.matches(this.config.checkAllSelector)) {
        this.toggleCheckAll(target.checked);
        return;
      }

      // 개별 체크박스 클릭
      if (target.matches(`${this.config.tableBodySelector} input[type="checkbox"]`)) {
        this.checkIndividual(target);
        return;
      }
    });

    // 검색창에서 엔터
    const searchInput = this.$(this.config.searchInputSelector);
    if (searchInput) {
      searchInput.addEventListener('keydown', e => {
        if (e.key === 'Enter') {
          e.preventDefault();
          this.$(this.config.searchBtnSelector).click();
        }
      });
    }
  }

  // ===============================
  // 모달 제어
  // ===============================
  openModal(modalId) {
    const modal = this.$(`#${modalId}`);
    if (modal) {
      modal.style.display = 'block';
    }
  }

  closeModal(modalId) {
    const modal = this.$(`#${modalId}`);
    if (modal) {
      modal.style.display = 'none';
    }
  }

  // ===============================
  // CRUD 및 기타 기능
  // ===============================
  openAddModal() {
    this.openModal(this.config.modalId.replace('#', ''));
    // 필요한 경우 모달 폼 초기화
  }

  openDetailModal(id) {
    // 상세 정보 조회 로직 (API 호출 등)
    console.log("상세 정보 조회:", id);
    this.openModal(this.config.detailModalId.replace('#', ''));
  }

  saveItem() {
    console.log("아이템 저장");
    // 저장 로직 (API 호출 등)
    this.closeModal(this.config.modalId.replace('#', ''));
    this.loadList(this.currentPage);
  }
  
  updateItem() {
    console.log("아이템 수정");
    // 수정 로직 (API 호출 등)
    this.closeModal(this.config.detailModalId.replace('#', ''));
    this.loadList(this.currentPage);
  }
  
  deleteItem(id) {
    if (confirm(`항목 ${id}을(를) 삭제하시겠습니까?`)) {
      console.log("단건 삭제 요청:", id);
      // 삭제 로직 (API 호출 등)
      this.closeModal(this.config.detailModalId.replace('#', ''));
      this.loadList(this.currentPage);
    }
  }

  deleteSelectedItems() {
    const selectedIds = Array.from(this.$$(`${this.config.tableBodySelector} input[type="checkbox"]:checked`)).map(cb => cb.value);
    if (selectedIds.length === 0) {
      alert("삭제할 항목을 선택해주세요.");
      return;
    }
    if (confirm(`${selectedIds.length}개의 항목을 삭제하시겠습니까?`)) {
      console.log("삭제 요청:", selectedIds);
      // 삭제 로직 (API 호출 등)
      this.loadList(this.currentPage);
    }
  }

  downloadExcel() {
    // 엑셀 다운로드 로직
    alert("엑셀 다운로드 기능");
  }

  toggleCheckAll(checked) {
    this.$$(`${this.config.tableBodySelector} input[type="checkbox"]`).forEach(cb => cb.checked = checked);
  }

  checkIndividual(target) {
    const checkAllEl = this.$(this.config.checkAllSelector);
    if (!checkAllEl) return;
    const allChecked = this.$$(`${this.config.tableBodySelector} input[type="checkbox"]`).length === this.$$(`${this.config.tableBodySelector} input[type="checkbox"]:checked`).length;
    checkAllEl.checked = allChecked;
  }

  fetchOptions(method, body = null) {
    const options = {
      method: method,
      headers: {
        'Content-Type': 'application/json'
      }
    };
    if (this.csrfToken && this.csrfHeader) {
      options.headers[this.csrfHeader] = this.csrfToken;
    }
    if (body) {
      options.body = JSON.stringify(body);
    }
    return options;
  }
}
