document.addEventListener("DOMContentLoaded", () => {
  const sidebar = document.querySelector(".sidebar");
  const overlay = document.getElementById("sidebarOverlay");

  // 사이드바 닫힌 상태로 시작
  sidebar.classList.remove("open");
  overlay.classList.remove("active");

  // 전역 토글 함수
  window.toggleSidebar = function() {
    const isOpen = sidebar.classList.contains("open");
    if (isOpen) {
      sidebar.classList.remove("open");
      overlay.classList.remove("active");
    } else {
      sidebar.classList.add("open");
      overlay.classList.add("active");
    }
  };

  // 오버레이 클릭 시 닫기
  overlay.addEventListener("click", () => {
    sidebar.classList.remove("open");
    overlay.classList.remove("active");
  });

  // 메뉴 클릭 시 닫기
  document.addEventListener("click", (e) => {
    if (e.target.closest(".sidebar a[href]")) {
      sidebar.classList.remove("open");
      overlay.classList.remove("active");
    }
  });
});
