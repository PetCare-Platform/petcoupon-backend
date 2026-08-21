(function () {
  "use strict";

  const MOBILE_QUERY = "(max-width: 960px)";
  const AREA_CONFIG = {
    public: {
      label: "서비스",
      home: "index.html",
      routes: [
        { page: "index", label: "이벤트", href: "index.html" },
        { page: "event-detail", label: "이벤트 상세", href: "event-detail.html" }
      ]
    },
    user: {
      label: "사용자",
      home: "user/user.html",
      routes: [
        { page: "user", label: "사용자 정보", href: "user/user.html" },
        { page: "my-coupons", label: "보유 쿠폰", href: "user/my-coupons.html" },
        { page: "coupon-detail", label: "쿠폰 상세", href: "user/coupon-detail.html" }
      ]
    },
    admin: {
      label: "관리자",
      home: "admin/admin.html",
      routes: [
        { page: "admin", label: "관리자 홈", href: "admin/admin.html" },
        { page: "events", label: "이벤트 목록", href: "admin/events.html" },
        { page: "event-form", label: "이벤트 편집", href: "admin/event-form.html" },
        { page: "coupons", label: "쿠폰 목록", href: "admin/coupons.html" },
        { page: "coupon-form", label: "쿠폰 편집", href: "admin/coupon-form.html" }
      ]
    },
    internal: {
      label: "내부 운영",
      home: "internal/monitoring.html",
      routes: [
        { page: "monitoring", label: "시스템 현황", href: "internal/monitoring.html" },
        { page: "issues", label: "발급 처리 흐름", href: "internal/issues.html" },
        { page: "failures", label: "실패 처리", href: "internal/failures.html" },
        { page: "verification", label: "정합성 검증", href: "internal/verification.html" }
      ]
    }
  };

  let toastTimer = null;

  function createElement(tagName, className, text) {
    const element = document.createElement(tagName);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function normalizePathname(pathname) {
    let normalized = pathname;
    try {
      normalized = decodeURIComponent(pathname);
    } catch (_error) {
      normalized = pathname;
    }
    normalized = normalized.replace(/\/+/g, "/");
    if (normalized.endsWith("/")) normalized += "index.html";
    return normalized.toLocaleLowerCase("en-US");
  }

  function isCurrentUrl(href) {
    const current = new URL(window.location.href);
    const target = new URL(href, current);
    return target.origin === current.origin
      && normalizePathname(target.pathname) === normalizePathname(current.pathname);
  }

  function pageHref(root, href) {
    return `${root}${href}`;
  }

  function createLink(label, href, className) {
    const link = createElement("a", className, label);
    link.setAttribute("href", href);
    if (isCurrentUrl(href)) link.setAttribute("aria-current", "page");
    return link;
  }

  function renderSiteHeader(context) {
    const placeholder = document.querySelector("[data-site-header]");
    if (!placeholder) return;

    const area = AREA_CONFIG[context.area];
    const header = createElement("header", "site-header top-nav");
    header.dataset.area = context.area;
    header.dataset.page = context.page;

    const inner = createElement("div", "nav-inner");
    const brand = createLink("PetCoupon", pageHref(context.root, AREA_CONFIG.public.home), "brand");
    brand.setAttribute("aria-label", "PetCoupon 홈");
    const prototypeBadge = createElement("span", "prototype-badge", "PROTOTYPE");
    prototypeBadge.setAttribute("aria-label", "프로토타입 화면");

    const panel = createElement("div", "nav-panel");
    panel.id = "site-navigation";
    const navLinks = createElement("div", "nav-links");

    const globalNav = createElement("nav", "global-nav");
    globalNav.setAttribute("aria-label", "전체 영역");
    Object.entries(AREA_CONFIG).forEach(function ([areaKey, areaConfig]) {
      const link = createLink(
        areaConfig.label,
        pageHref(context.root, areaConfig.home),
        "global-nav__link"
      );
      link.dataset.area = areaKey;
      globalNav.append(link);
    });

    const areaNav = createElement("nav", "area-nav section-nav");
    areaNav.setAttribute("aria-label", `${area.label} 메뉴`);
    areaNav.append(createElement("span", "area-nav__label", area.label));
    area.routes.forEach(function (route) {
      const link = createLink(route.label, pageHref(context.root, route.href), "area-nav__link");
      link.dataset.page = route.page;
      areaNav.append(link);
    });

    navLinks.append(globalNav, areaNav);
    const navActions = createElement("div", "nav-actions");
    navActions.append(createLink(
      `${area.label} 홈`,
      pageHref(context.root, area.home),
      "nav-action"
    ));
    panel.append(navLinks, navActions);

    const toggle = createElement("button", "nav-toggle", "메뉴");
    toggle.type = "button";
    toggle.setAttribute("aria-controls", panel.id);
    toggle.setAttribute("aria-expanded", "false");
    toggle.setAttribute("aria-label", "메뉴 열기");

    inner.append(brand, prototypeBadge, panel, toggle);
    header.append(inner);
    const marquee = renderMarquee();
    placeholder.replaceChildren(header, marquee);
    setupMobileNavigation(header, panel, toggle);
  }

  function renderMarquee() {
    const marquee = createElement("div", "marquee marquee-strip");
    marquee.setAttribute("aria-hidden", "true");
    const track = createElement("div", "marquee-track");
    const labels = ["PETCOUPON", "EVENTS", "COUPONS", "RELIABLE OPERATIONS"];
    for (let repeat = 0; repeat < 2; repeat += 1) {
      labels.forEach(function (label) {
        track.append(createElement("span", "marquee-item", label));
      });
    }
    marquee.append(track);
    return marquee;
  }

  function setupMobileNavigation(header, panel, toggle) {
    const mobile = window.matchMedia(MOBILE_QUERY);
    const firstLink = panel.querySelector("a[href]");

    function applyState(open, focusTarget) {
      const nextOpen = mobile.matches && open;
      toggle.setAttribute("aria-expanded", String(nextOpen));
      toggle.setAttribute("aria-label", nextOpen ? "메뉴 닫기" : "메뉴 열기");
      header.classList.toggle("is-open", nextOpen);
      panel.classList.toggle("is-open", nextOpen);
      document.body.classList.toggle("nav-open", nextOpen);
      toggle.hidden = !mobile.matches;
      panel.hidden = mobile.matches && !nextOpen;
      if (panel.hidden) panel.setAttribute("aria-hidden", "true");
      else panel.removeAttribute("aria-hidden");

      if (focusTarget === "first" && firstLink) {
        window.requestAnimationFrame(function () { firstLink.focus(); });
      }
      if (focusTarget === "toggle") toggle.focus();
    }

    function resetForViewport() {
      const focusWasInPanel = panel.contains(document.activeElement);
      applyState(false, mobile.matches && focusWasInPanel ? "toggle" : null);
    }

    toggle.addEventListener("click", function () {
      const willOpen = toggle.getAttribute("aria-expanded") !== "true";
      applyState(willOpen, willOpen ? "first" : null);
    });

    panel.addEventListener("click", function (event) {
      if (mobile.matches && event.target.closest("a[href]")) applyState(false, null);
    });

    document.addEventListener("keydown", function (event) {
      if (event.key !== "Escape" || toggle.getAttribute("aria-expanded") !== "true") return;
      event.preventDefault();
      applyState(false, "toggle");
    });

    if (typeof mobile.addEventListener === "function") mobile.addEventListener("change", resetForViewport);
    else mobile.addListener(resetForViewport);
    resetForViewport();
  }

  function renderSiteFooter(context) {
    const placeholder = document.querySelector("[data-site-footer]");
    if (!placeholder) return;

    const footer = createElement("footer", "site-footer footer");
    const inner = createElement("div", "footer-inner footer-grid");
    const brand = createLink("PetCoupon", pageHref(context.root, AREA_CONFIG.public.home), "footer-brand");
    const footerNav = createElement("nav", "footer-nav");
    footerNav.setAttribute("aria-label", "푸터 영역");
    Object.values(AREA_CONFIG).forEach(function (areaConfig) {
      footerNav.append(createLink(
        areaConfig.label,
        pageHref(context.root, areaConfig.home),
        "footer-nav__link"
      ));
    });

    const meta = createElement("p", "footer-meta");
    meta.append("© ");
    const year = createElement("span", "", String(new Date().getFullYear()));
    year.setAttribute("data-current-year", "");
    meta.append(year, " PetCoupon");
    inner.append(brand, footerNav, meta);
    footer.append(inner);
    placeholder.replaceChildren(footer);
  }

  function getFilterRoots(group, controls) {
    const roots = [];
    controls.forEach(function (control) {
      String(control.getAttribute("aria-controls") || "")
        .split(/\s+/)
        .filter(Boolean)
        .forEach(function (targetId) {
          const target = document.getElementById(targetId);
          if (target && !roots.includes(target)) roots.push(target);
        });
    });
    if (roots.length) return roots;
    return [group.closest("[data-filter-scope]") || group.closest("section") || document];
  }

  function getFilterFeedbackNodes(group, roots, selector) {
    const region = group.closest("[data-filter-scope]") || group.closest("section") || roots[0].parentElement;
    const local = region ? Array.from(region.querySelectorAll(selector)) : [];
    if (local.length) return local;
    const allGroups = document.querySelectorAll("[data-filter-group]");
    return allGroups.length === 1 ? Array.from(document.querySelectorAll(selector)) : [];
  }

  function setupFilters() {
    document.querySelectorAll("[data-filter-group]").forEach(function (group) {
      const controls = Array.from(group.querySelectorAll("[data-filter-value]"));
      if (!controls.length) return;
      const roots = getFilterRoots(group, controls);
      const itemSet = new Set();
      roots.forEach(function (root) {
        root.querySelectorAll("[data-filter-item][data-status]").forEach(function (item) {
          itemSet.add(item);
        });
      });
      const items = Array.from(itemSet);
      const countNodes = getFilterFeedbackNodes(group, roots, "[data-result-count]");
      const emptyNodes = getFilterFeedbackNodes(group, roots, "[data-filter-empty]");

      countNodes.forEach(function (node) {
        node.setAttribute("role", "status");
        node.setAttribute("aria-live", "polite");
        if (!node.dataset.countUnit) {
          node.dataset.countUnit = node.textContent.trim().replace(/[\d,\s]/g, "") || "건";
        }
      });
      emptyNodes.forEach(function (node) {
        node.setAttribute("role", "status");
        node.setAttribute("aria-live", "polite");
      });

      function applyFilter(value) {
        const normalizedValue = String(value || "all").toLocaleLowerCase("en-US");
        const visibleRecordIds = new Set();
        let visibleWithoutId = 0;

        items.forEach(function (item) {
          const statuses = String(item.dataset.status || "")
            .toLocaleLowerCase("en-US")
            .split(/[\s,|]+/)
            .filter(Boolean);
          const visible = normalizedValue === "all" || statuses.includes(normalizedValue);
          item.hidden = !visible;
          if (!visible) return;
          if (item.dataset.recordId) visibleRecordIds.add(item.dataset.recordId);
          else visibleWithoutId += 1;
        });

        controls.forEach(function (control) {
          const active = String(control.dataset.filterValue || "all").toLocaleLowerCase("en-US") === normalizedValue;
          control.setAttribute("aria-pressed", String(active));
          control.classList.toggle("is-active", active);
        });

        const visibleCount = visibleRecordIds.size + visibleWithoutId;
        countNodes.forEach(function (node) {
          node.textContent = `${visibleCount}${node.dataset.countUnit || "건"}`;
        });
        emptyNodes.forEach(function (node) { node.hidden = visibleCount !== 0; });
      }

      controls.forEach(function (control) {
        control.addEventListener("click", function () {
          applyFilter(control.dataset.filterValue || "all");
        });
      });

      const initial = controls.find(function (control) {
        return control.getAttribute("aria-pressed") === "true";
      }) || controls[0];
      applyFilter(initial.dataset.filterValue || "all");
    });
  }

  function fallbackCopy(text) {
    const activeElement = document.activeElement;
    const textarea = createElement("textarea", "copy-fallback");
    textarea.value = text;
    textarea.setAttribute("readonly", "");
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    textarea.style.pointerEvents = "none";
    document.body.append(textarea);
    textarea.select();
    let copied = false;
    try {
      copied = document.execCommand("copy");
    } catch (_error) {
      copied = false;
    }
    textarea.remove();
    if (activeElement && typeof activeElement.focus === "function") activeElement.focus();
    return copied;
  }

  async function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch (_error) {
        return fallbackCopy(text);
      }
    }
    return fallbackCopy(text);
  }

  function setupCopyActions() {
    document.querySelectorAll("[data-copy-target]").forEach(function (button) {
      button.addEventListener("click", async function () {
        let target = null;
        try {
          target = document.querySelector(button.dataset.copyTarget);
        } catch (_error) {
          target = null;
        }
        if (!target) {
          showToast("복사할 대상을 확인해 주세요.");
          return;
        }
        const text = "value" in target ? String(target.value) : target.textContent.trim();
        if (!text) {
          showToast("복사할 내용이 없습니다.");
          return;
        }
        const copied = await copyText(text);
        showToast(copied ? "클립보드에 복사했습니다." : "복사하지 못했습니다.");
      });
    });
  }

  function setupDemoForms() {
    document.querySelectorAll("form[data-demo-form]").forEach(function (form) {
      form.addEventListener("submit", function (event) {
        event.preventDefault();
        if (typeof form.checkValidity === "function" && !form.checkValidity()) {
          if (typeof form.reportValidity === "function") form.reportValidity();
          showToast("입력 내용을 확인해 주세요.");
          return;
        }
        showToast(form.dataset.demoMessage || "데모 화면에서 제출 동작을 확인했습니다.");
      });
    });
  }

  function setupDemoActions() {
    document.querySelectorAll("[data-demo-action]").forEach(function (control) {
      control.addEventListener("click", function (event) {
        if (control.tagName === "A") event.preventDefault();
        const action = control.dataset.demoAction || "선택한";
        showToast(control.dataset.demoMessage || `${action} 동작은 데모 안내만 제공합니다.`);
      });
    });
  }

  function setupDiscountType() {
    const radios = Array.from(document.querySelectorAll('input[name="discountType"]'));
    const maxDiscount = document.querySelector('[name="maxDiscountAmount"]');
    if (!radios.length || !maxDiscount) return;
    const fieldGroup = maxDiscount.closest("fieldset, .form-group, .field-group, [data-field-group], [data-max-discount-field]");

    function update() {
      const selected = radios.find(function (radio) { return radio.checked; });
      const disabled = !selected || selected.value !== "RATE";
      maxDiscount.disabled = disabled;
      if (fieldGroup) {
        fieldGroup.classList.toggle("is-disabled", disabled);
        fieldGroup.setAttribute("aria-disabled", String(disabled));
      }
    }

    radios.forEach(function (radio) { radio.addEventListener("change", update); });
    update();
  }

  function showToast(message) {
    const toast = document.querySelector("[data-toast]");
    if (!toast) return;
    if (toastTimer) window.clearTimeout(toastTimer);
    toast.setAttribute("role", "status");
    toast.setAttribute("aria-live", "polite");
    toast.setAttribute("aria-atomic", "true");
    toast.textContent = message;
    toast.hidden = false;
    toastTimer = window.setTimeout(function () {
      toast.hidden = true;
      toastTimer = null;
    }, 3200);
  }

  function updateCurrentYear() {
    const year = String(new Date().getFullYear());
    document.querySelectorAll("[data-current-year]").forEach(function (node) {
      node.textContent = year;
    });
  }

  function init() {
    const requestedArea = document.body.dataset.area || "public";
    const context = {
      area: Object.prototype.hasOwnProperty.call(AREA_CONFIG, requestedArea) ? requestedArea : "public",
      page: document.body.dataset.page || "",
      root: document.body.dataset.root === "../" ? "../" : ""
    };

    renderSiteHeader(context);
    renderSiteFooter(context);
    setupFilters();
    setupCopyActions();
    setupDemoForms();
    setupDemoActions();
    setupDiscountType();
    updateCurrentYear();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();

