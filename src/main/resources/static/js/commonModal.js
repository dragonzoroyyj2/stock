document.addEventListener("DOMContentLoaded", () => {

  // =========================
  // 모달 열기 함수
  // =========================
  window.openModal = function(modalId) {
    const modal = document.querySelector(modalId);
    if (modal) {
      modal.classList.add("active");
    }
  };

  // =========================
  // 모달 닫기 함수
  // =========================
  window.closeModal = function(modalId) {
    const modal = document.querySelector(modalId);
    if (modal) {
      modal.classList.remove("active");
    }
  };

  // =========================
  // 닫기 버튼 이벤트
  // =========================
  document.querySelectorAll("[data-close]").forEach(btn => {
    btn.addEventListener("click", (e) => {
      const targetId = btn.getAttribute("data-close");
      closeModal("#" + targetId);
    });
  });

  // =========================
  // 오버레이 클릭 시 모달 닫기
  // =========================
  document.querySelectorAll(".modal").forEach(modal => {
    modal.addEventListener("click", (e) => {
      if (e.target === modal) {
        modal.classList.remove("active");
      }
    });
  });

});
