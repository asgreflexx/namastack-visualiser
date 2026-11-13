// Shared behaviour: CSRF for htmx, drawer, toasts, JSON pretty-printing, auto-refresh, dashboard charts.

(function () {
  const AUTO_REFRESH_KEY = "outbox-visualiser.autoRefresh";

  function readAutoRefresh() {
    try {
      return localStorage.getItem(AUTO_REFRESH_KEY) !== "false";
    } catch (e) {
      return true;
    }
  }

  window.autoRefresh = readAutoRefresh;

  document.addEventListener("htmx:configRequest", (event) => {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) {
      event.detail.headers[header.content] = token.content;
    }
  });

  // An expired session answers htmx requests with the login page; reload to show it properly.
  document.addEventListener("htmx:beforeSwap", (event) => {
    const url = event.detail.xhr && event.detail.xhr.responseURL;
    if (url && url.includes("/login")) {
      event.detail.shouldSwap = false;
      window.location.reload();
    }
  });

  document.addEventListener("htmx:responseError", (event) => {
    const status = event.detail.xhr.status;
    const message = status === 403
      ? "Diese Aktion braucht die Rolle OPERATOR."
      : status === 409
        ? "Diese Quelle ist schreibgeschützt."
        : "Anfrage fehlgeschlagen (HTTP " + status + ").";
    toast(message, "warn");
  });

  document.addEventListener("htmx:afterSwap", () => prettyPrint(document));
  document.addEventListener("DOMContentLoaded", () => {
    prettyPrint(document);
    const toggle = document.getElementById("auto-refresh");
    if (toggle) {
      toggle.checked = readAutoRefresh();
      toggle.addEventListener("change", () => {
        try {
          localStorage.setItem(AUTO_REFRESH_KEY, String(toggle.checked));
        } catch (e) {
          // Storage unavailable: the setting just isn't remembered.
        }
      });
    }
    const dashboard = document.getElementById("dashboard-charts");
    if (dashboard) {
      initCharts(dashboard);
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeDrawer();
    }
  });

  document.addEventListener("click", (event) => {
    if (event.target.closest("[data-close-drawer]")) {
      closeDrawer();
    }
  });

  function closeDrawer() {
    const drawer = document.getElementById("drawer");
    if (drawer) {
      drawer.innerHTML = "";
    }
  }

  function toast(message, level) {
    const container = document.getElementById("toasts");
    if (!container) return;
    const element = document.createElement("div");
    element.className = "toast" + (level ? " " + level : "");
    element.textContent = message;
    container.appendChild(element);
  }

  // Toasts swapped in out-of-band disappear after a while.
  new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      mutation.addedNodes.forEach((node) => {
        if (node.nodeType === 1 && node.classList.contains("toast")) {
          setTimeout(() => node.remove(), 5000);
        }
      });
    }
  }).observe(document.documentElement, { childList: true, subtree: true });

  function prettyPrint(root) {
    root.querySelectorAll("pre[data-json]:not([data-pretty])").forEach((pre) => {
      pre.setAttribute("data-pretty", "");
      try {
        pre.textContent = JSON.stringify(JSON.parse(pre.textContent), null, 2);
      } catch (e) {
        // Not JSON (e.g. a custom serializer): show as stored.
      }
    });
  }

  // ---------- Dashboard charts ----------

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function initCharts(container) {
    if (typeof Chart === "undefined") return;
    const source = container.dataset.source || "";
    const windowName = container.dataset.window;
    const ink = cssVar("--ink-soft");
    const rule = cssVar("--rule");
    Chart.defaults.font.family = cssVar("--sans");
    Chart.defaults.color = ink;

    const throughput = new Chart(document.getElementById("throughput-chart"), {
      type: "line",
      data: {
        labels: [],
        datasets: [
          dataset("Erstellt", cssVar("--new")),
          dataset("Abgeschlossen", cssVar("--done")),
        ],
      },
      options: {
        animation: false,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { display: false }, ticks: { maxTicksLimit: 10, autoSkip: true } },
          y: { beginAtZero: true, grid: { color: rule }, ticks: { precision: 0 } },
        },
      },
    });

    const failures = new Chart(document.getElementById("failures-chart"), {
      type: "bar",
      data: { labels: [], datasets: [{ data: [], backgroundColor: cssVar("--failed"), borderRadius: 3, maxBarThickness: 18 }] },
      options: {
        indexAxis: "y",
        animation: false,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: { callbacks: { title: (items) => failures.$meta[items[0].dataIndex] } },
        },
        scales: {
          x: { beginAtZero: true, grid: { color: rule }, ticks: { precision: 0 } },
          y: { grid: { display: false }, afterFit: (scale) => { scale.width = Math.max(scale.width, 210); } },
        },
      },
    });
    failures.$meta = [];

    const query = "?source=" + encodeURIComponent(source);

    async function refresh() {
      try {
        const [t, f] = await Promise.all([
          fetch("/api/throughput" + query + "&window=" + windowName).then(checked),
          fetch("/api/failures" + query).then(checked),
        ]);
        const withDate = t.bucketSeconds >= 900;
        throughput.data.labels = t.buckets.map((iso) => formatTime(iso, withDate));
        throughput.data.datasets[0].data = t.created;
        throughput.data.datasets[1].data = t.completed;
        throughput.update();

        failures.data.labels = f.map((g) => g.handler);
        failures.data.datasets[0].data = f.map((g) => g.count);
        failures.$meta = f.map((g) => g.handlerId + "\n" + g.recordType);
        failures.update();
        document.getElementById("failures-empty").hidden = f.length > 0;
      } catch (e) {
        // Next refresh will try again; the summary above shows source errors.
      }
    }

    function checked(response) {
      if (response.redirected || !response.ok) {
        throw new Error("HTTP " + response.status);
      }
      return response.json();
    }

    refresh();
    // Re-measure axis labels once the web font is in, otherwise long handler names get clipped.
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(() => {
        throughput.resize();
        failures.resize();
      });
    }
    setInterval(() => {
      if (readAutoRefresh()) refresh();
    }, 5000);
  }

  function dataset(label, color) {
    return {
      label,
      data: [],
      borderColor: color,
      backgroundColor: color + "22",
      fill: true,
      tension: 0.3,
      pointRadius: 0,
      borderWidth: 2,
    };
  }

  function formatTime(iso, withDate) {
    const date = new Date(iso);
    const time = date.toLocaleTimeString("de-AT", { hour: "2-digit", minute: "2-digit" });
    return withDate ? date.toLocaleDateString("de-AT", { day: "2-digit", month: "2-digit" }) + " " + time : time;
  }
})();
