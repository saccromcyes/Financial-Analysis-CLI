"use strict";
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
let CUR = "$";
let dataset = "sample";
const money = (v) => `${CUR}${Number(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const monthName = (ym) => new Date(`${ym}-01T00:00:00`).toLocaleDateString("en-US", { month: "short" });

async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.detail || `Request failed (${res.status}).`);
  return body;
}

// ---------- Ask ----------
function chips(q) {
  const out = [];
  const metric = { sum: "total", count: "count", average: "average", max: "largest", min: "smallest", list: "list" }[q.metric] || q.metric;
  out.push(metric);
  if (q.type) out.push(q.type === "DEBIT" ? "money out" : "money in");
  if (q.categories?.length) out.push(q.categories.join(" + "));
  if (q.merchant) out.push(`merchant: ${q.merchant}`);
  if (q.text) out.push(`description has "${q.text}"`);
  if (q.from || q.to) out.push(`${q.from || "start"} to ${q.to || "end"}`);
  if (q.min_amount != null) out.push(`over ${money(q.min_amount)}`);
  if (q.max_amount != null) out.push(`under ${money(q.max_amount)}`);
  if (q.group_by && q.group_by !== "none") out.push(`by ${q.group_by}`);
  if ((q.group_by && q.group_by !== "none") || q.metric === "list") out.push(`${q.sort === "asc" ? "lowest" : "highest"} first, up to ${q.limit}`);
  return out.map((c) => `<span class="chip">${esc(c)}</span>`).join("");
}

function detail(r) {
  if (r.groups.length > 1) {
    const max = Math.max(...r.groups.map((g) => Math.abs(g.value))) || 1;
    const isCount = r.query.metric === "count";
    return `<table class="detail-table"><thead><tr><th>${esc(r.query.group_by)}</th><th class="n">${isCount ? "Transactions" : "Amount"}</th></tr></thead><tbody>${
      r.groups.map((g) => `<tr><td><span class="gbar" style="width:${Math.round((Math.abs(g.value) / max) * 120)}px"></span>${esc(r.query.group_by === "month" ? `${monthName(g.key)} ${g.key.slice(0, 4)}` : g.key)}</td><td class="n">${isCount ? g.value : money(g.value)}</td></tr>`).join("")
    }</tbody></table>`;
  }
  if (r.rows.length && r.query.metric === "list") {
    return `<table class="detail-table"><thead><tr><th>Date</th><th>Description</th><th>Merchant</th><th class="n">Amount</th></tr></thead><tbody>${
      r.rows.map((t) => `<tr><td>${esc(t.date)}</td><td>${esc(t.description)}</td><td>${esc(t.merchant)}</td><td class="n">${t.type === "CREDIT" ? "+" : ""}${money(t.amount)}</td></tr>`).join("")
    }</tbody></table>`;
  }
  return "";
}

async function ask(question) {
  if (!question.trim()) return;
  $("#ask-btn").disabled = true;
  $("#ask-error").hidden = true;
  try {
    const r = await api("/api/ask", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ question, dataset }) });
    $("#answer").hidden = false;
    $("#answer-text").textContent = r.answer;
    $("#answer-notice").hidden = !r.notice;
    $("#answer-notice").textContent = r.notice || "";
    $("#how-label").textContent = r.planned_by === "llm" ? "Read by the language model as:" : "Read by keyword rules as:";
    $("#chips").innerHTML = chips(r.query);
    $("#answer-detail").innerHTML = detail(r);
  } catch (e) {
    $("#ask-error").textContent = e.message;
    $("#ask-error").hidden = false;
  } finally {
    $("#ask-btn").disabled = false;
  }
}
$("#ask-form").addEventListener("submit", (e) => { e.preventDefault(); ask($("#question").value); });
$("#examples").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b) { $("#question").value = b.textContent; ask(b.textContent); } });

// ---------- Dashboard ----------
function chart(months, forecast) {
  const all = [...months.map((m) => ({ ...m, f: false })), ...forecast.map((f) => ({ month: f.month, income: 0, spent: f.spent, f: true }))];
  const W = 960, H = 240, pad = 28, slot = (W - pad) / all.length, bw = Math.min(16, slot / 3);
  const max = Math.max(...all.map((m) => Math.max(m.income, m.spent))) * 1.08 || 1;
  const y = (v) => H - 22 - (v / max) * (H - 36);
  let svg = `<svg viewBox="0 0 ${W} ${H}" aria-hidden="true">`;
  for (let i = 0; i <= 3; i++) {
    const v = (max / 3) * i;
    svg += `<line x1="${pad}" x2="${W}" y1="${y(v)}" y2="${y(v)}" stroke="var(--rule)"/><text x="0" y="${y(v) + 4}">${v >= 1000 ? `${Math.round(v / 1000)}k` : Math.round(v)}</text>`;
  }
  all.forEach((m, i) => {
    const x = pad + i * slot + slot / 2;
    if (!m.f) svg += `<rect x="${x - bw - 1}" y="${y(m.income)}" width="${bw}" height="${H - 22 - y(m.income)}" rx="2" fill="var(--in)"><title>${m.month} income ${money(m.income)}</title></rect>`;
    svg += m.f
      ? `<rect x="${x + 1}" y="${y(m.spent)}" width="${bw}" height="${H - 22 - y(m.spent)}" rx="2" fill="none" stroke="var(--out)" stroke-dasharray="3 2"><title>${m.month} forecast ${money(m.spent)}</title></rect>`
      : `<rect x="${x + 1}" y="${y(m.spent)}" width="${bw}" height="${H - 22 - y(m.spent)}" rx="2" fill="var(--out)"><title>${m.month} spending ${money(m.spent)}</title></rect>`;
    svg += `<text x="${x}" y="${H - 6}" text-anchor="middle">${monthName(m.month)}</text>`;
  });
  return svg + "</svg>";
}

async function loadSummary() {
  const d = await api(`/api/summary?d=${encodeURIComponent(dataset)}`);
  CUR = d.currency || "$";
  const k = d.kpis;
  $("#kpis").innerHTML = `
    <div class="kpi"><span>Money in</span><b class="in">${money(k.income)}</b></div>
    <div class="kpi"><span>Money out</span><b class="out">${money(k.spent)}</b></div>
    <div class="kpi"><span>Net</span><b>${money(k.net)}</b></div>
    <div class="kpi"><span>Saved</span><b>${Math.round(k.savings_rate * 100)}%</b></div>`;
  $("#chart").innerHTML = chart(d.months, d.forecast);
  $("#chart").setAttribute("aria-label", `Monthly income and spending from ${k.from} to ${k.to}`);
  const top = d.categories[0]?.spent || 1;
  $("#categories").innerHTML = d.categories.slice(0, 8).map((c) => `<li><span class="name">${esc(c.category)}</span><i style="width:${Math.max(2, (c.spent / top) * 100)}%"></i><span>${money(c.spent)}</span></li>`).join("");
  $("#anomalies").innerHTML = d.anomalies.length
    ? d.anomalies.map((a) => `<li><b>${money(a.amount)}</b> at ${esc(a.merchant)} on ${esc(a.date)}<span class="why">${esc(a.reason)}</span></li>`).join("")
    : `<li>Nothing unusual found.</li>`;
  $("#recurring").innerHTML = d.recurring.length
    ? d.recurring.map((r) => `<li><span>${esc(r.merchant)}, ${money(r.amount)}</span><span>${money(r.yearly_cost)} a year</span></li>`).join("")
    : `<li>No recurring charges found.</li>`;
}

// ---------- Upload ----------
$("#file").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  e.target.value = "";
  if (!file) return;
  const status = $("#upload-status");
  status.textContent = `Reading ${file.name}…`;
  try {
    if (file.size > 1_000_000) throw new Error("Files must be under 1 MB.");
    const r = await api("/api/datasets", { method: "POST", headers: { "Content-Type": "text/csv" }, body: await file.text() });
    dataset = r.id;
    status.textContent = `Loaded ${r.transactions} transactions from ${file.name}. Questions now use your data.`;
    $("#dataset-label").textContent = `Your data: ${file.name}`;
    $("#use-sample").hidden = false;
    $("#answer").hidden = true;
    await loadSummary();
  } catch (err) {
    status.textContent = err.message;
  }
});
$("#use-sample").addEventListener("click", async () => {
  dataset = "sample";
  $("#dataset-label").textContent = "Sample data: one year of synthetic transactions";
  $("#use-sample").hidden = true;
  $("#upload-status").textContent = "";
  $("#answer").hidden = true;
  await loadSummary();
});

// ---------- Evaluation ----------
async function loadEval() {
  const d = await api("/api/eval").catch(() => ({ available: false }));
  if (!d.planners) { $("#eval-body").innerHTML = `<p class="muted">Results appear after the first evaluation run.</p>`; return; }
  const names = { rules: "Keyword rules", llm: `Language model${d.planners.llm?.model ? ` (${d.planners.llm.model})` : ""}` };
  $("#eval-body").innerHTML = Object.entries(d.planners).map(([k, p]) => `
    <div class="eval-row"><span>${esc(names[k] || k)}</span><i><b style="width:${p.accuracy * 100}%"></b></i><strong>${Math.round(p.accuracy * 100)}%</strong></div>`).join("")
    + Object.entries(d.planners).map(([k, p]) => {
      const missed = p.results.filter((r) => !r.correct);
      return missed.length ? `<details><summary>${esc(names[k] || k)} missed ${missed.length}</summary><ul>${missed.map((r) => `<li>${esc(r.question)}</li>`).join("")}</ul></details>` : "";
    }).join("");
}

loadSummary().catch((e) => { $("#kpis").innerHTML = `<p class="error">${esc(e.message)}</p>`; });
loadEval();
