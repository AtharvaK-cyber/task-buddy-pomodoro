const backendBase = window.location.origin;
const el = id => document.getElementById(id);

function qs(selector){ return document.querySelector(selector); }

// --- UI bindings
el("taskForm").addEventListener("submit", async e => {
  e.preventDefault();
  const title = encodeURIComponent(el("title").value.trim());
  const due = encodeURIComponent(el("due").value);
  const tags = encodeURIComponent(el("tags").value.trim());
  if (!title) return alert("Enter a title");
  await fetch(backendBase + "/addTask", { method: "POST", headers: {"Content-Type":"application/x-www-form-urlencoded"}, body: `title=${title}&due=${due}&tags=${tags}` });
  el("title").value = ""; el("due").value = ""; el("tags").value = "";
  loadTasks();
});

el("refreshBtn").addEventListener("click", loadTasks);
el("sortSelect").addEventListener("change", loadTasks);
el("exportCsv").addEventListener("click", () => window.location = backendBase + "/exportCSV");
el("searchInput").addEventListener("input", () => loadTasks());

const darkToggle = el("darkToggle");
darkToggle.addEventListener("click", () => { document.body.classList.toggle("dark"); localStorage.setItem("dark", document.body.classList.contains("dark"));});
if (localStorage.getItem("dark") === "true") document.body.classList.add("dark");

// --- load & render
async function loadTasks() {
  try {
    const res = await fetch(backendBase + "/tasks");
    const data = await res.json();
    renderTasks(data);
    fillPomodoroTaskSelect(data);
    drawPriorityChart(data);
  } catch (e) {
    console.error("Failed to load tasks", e);
    el("taskList").innerHTML = "<p>Unable to load tasks. Is backend running?</p>";
  }
}

function renderTasks(tasks) {
  const q = el("searchInput").value.trim().toLowerCase();
  let filtered = tasks;
  if (q) filtered = tasks.filter(t => (t.title||"").toLowerCase().includes(q) || (t.tags||"").toLowerCase().includes(q));

  const sort = el("sortSelect").value;
  if (sort === "due") filtered.sort((a,b) => (a.due||"").localeCompare(b.due||""));
  if (sort === "title") filtered.sort((a,b) => (a.title||"").localeCompare(b.title||""));

  const container = el("taskList"); container.innerHTML = "";
  if (!filtered || filtered.length === 0) { container.innerHTML = "<p>No tasks yet.</p>"; el("summary").innerText = "0 tasks"; return; }

  const total = filtered.length;
  const completed = filtered.filter(t => t.completed).length;
  el("summary").innerText = `${total} task(s) — ${completed} completed`;

  filtered.forEach(t => {
    const div = document.createElement("div");
    div.className = "task " + (t.priority === "High" ? "priority-high" : t.priority === "Medium" ? "priority-medium" : "priority-low");

    const left = document.createElement("div"); left.className = "task-left";
    const title = document.createElement("div");
    title.innerHTML = `<strong>${escapeHtml(t.title)}</strong> ${t.tags ? '<small>· ' + escapeHtml(t.tags) + '</small>' : ''}`;
    const meta = document.createElement("div"); meta.className = "meta";
    const days = computeDaysLeft(t.due);
    meta.innerHTML = `Due: ${t.due} • ${days} day(s) left • Priority: ${t.priority}`;
    if (days !== "past" && Number(days) <= 3) meta.innerHTML += '<span class="badge-soon">Due soon</span>';
    left.appendChild(title); left.appendChild(meta);

    const right = document.createElement("div"); right.className = "task-actions";
    const checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.checked = t.completed;
    checkbox.addEventListener("change", async () => { await postForm("/toggleComplete", {id: t.id}); loadTasks(); });

    const edit = document.createElement("button"); edit.className = "small-btn"; edit.innerText = "Edit";
    edit.addEventListener("click", () => editTaskUI(t));

    const del = document.createElement("button"); del.className = "delete-btn"; del.innerText = "Delete";
    del.addEventListener("click", async () => {
      if (!confirm("Delete this task?")) return;
      await postForm("/deleteTask", {id: t.id}); loadTasks();
    });

    if (t.completed) left.classList.add("completed");
    right.appendChild(checkbox); right.appendChild(edit); right.appendChild(del);
    div.appendChild(left); div.appendChild(right);
    container.appendChild(div);
  });
}

function editTaskUI(t) {
  const newTitle = prompt("Edit title:", t.title);
  if (newTitle === null) return;
  const newDue = prompt("Edit due (YYYY-MM-DD):", t.due);
  if (newDue === null) return;
  const newTags = prompt("Edit tags (comma separated):", t.tags || "");
  if (newTags === null) return;
  postForm("/editTask", {id: t.id, title: newTitle, due: newDue, tags: newTags}).then(loadTasks);
}

// --- utilities
function computeDaysLeft(due) {
  try {
    if (!due) return "N/A";
    const now = new Date();
    const d = new Date(due + "T00:00:00");
    const diff = Math.ceil((d - new Date(now.getFullYear(), now.getMonth(), now.getDate())) / (1000*60*60*24));
    return diff >= 0 ? diff : "past";
  } catch (e) { return "N/A"; }
}
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch])); }
async function postForm(path, obj) {
  const body = Object.keys(obj).map(k => encodeURIComponent(k) + "=" + encodeURIComponent(obj[k])).join("&");
  await fetch(backendBase + path, { method: "POST", headers: {"Content-Type":"application/x-www-form-urlencoded"}, body });
}

// --- Pomodoro logic
let pomInterval = null;
let pomRemaining = 25*60;
let currentSessionId = null;

function fillPomodoroTaskSelect(tasks) {
  const sel = el("pomTaskSelect");
  sel.innerHTML = "";
  tasks.forEach(t => {
    const opt = document.createElement("option");
    opt.value = t.id; opt.text = `${t.title} (${t.due})`; sel.appendChild(opt);
  });
}

el("startPom").addEventListener("click", async () => {
  const taskId = el("pomTaskSelect").value;
  if (!taskId) return alert("Select a task");
  const resp = await fetch(backendBase + "/pomodoro/start", {
    method: "POST", headers: {"Content-Type":"application/x-www-form-urlencoded"}, body: `taskId=${encodeURIComponent(taskId)}`
  });
  currentSessionId = await resp.text();
  pomRemaining = 25*60;
  el("startPom").disabled = true; el("stopPom").disabled = false;
  startCountdown();
});

el("stopPom").addEventListener("click", async () => {
  if (!currentSessionId) return;
  await fetch(backendBase + "/pomodoro/stop", {
    method: "POST", headers: {"Content-Type":"application/x-www-form-urlencoded"}, body: `sessionId=${encodeURIComponent(currentSessionId)}`
  });
  currentSessionId = null;
  stopCountdown();
  notify("Pomodoro finished", "Well done — session logged!");
  loadTasks();
});

function startCountdown() {
  updatePomDisplay();
  pomInterval = setInterval(() => {
    if (pomRemaining <= 0) { el("stopPom").click(); return; }
    pomRemaining--; updatePomDisplay();
  }, 1000);
}
function stopCountdown() { if (pomInterval) clearInterval(pomInterval); pomInterval = null; el("startPom").disabled = false; el("stopPom").disabled = true; el("pomTimer").innerText = "25:00"; }
function updatePomDisplay() { const mm = Math.floor(pomRemaining/60).toString().padStart(2,'0'); const ss = Math.floor(pomRemaining%60).toString().padStart(2,'0'); el("pomTimer").innerText = `${mm}:${ss}`; }
function notify(title, body) { if (!("Notification" in window)) return; if (Notification.permission === "granted") new Notification(title, { body }); else if (Notification.permission !== "denied") Notification.requestPermission().then(p => { if (p === "granted") new Notification(title, {body}); }); }

// --- Priority chart
function drawPriorityChart(tasks) {
  const counts = { High:0, Medium:0, Low:0 };
  tasks.forEach(t => counts[t.priority] = (counts[t.priority]||0) + 1);
  const canvas = el("priorityChart"); const ctx = canvas.getContext("2d");
  ctx.clearRect(0,0,canvas.width,canvas.height);
  const keys = ["High","Medium","Low"];
  const max = Math.max(...keys.map(k=>counts[k]),1);
  keys.forEach((k,i) => {
    const w = (canvas.width-140) * (counts[k]/max);
    const y = 20 + i*40;
    ctx.fillStyle = k==="High" ? "#e65151" : k==="Medium" ? "#f0ad4e" : "#4caf50";
    ctx.fillRect(140, y, w, 28);
    ctx.fillStyle = "#222";
    ctx.fillText(`${k} (${counts[k]})`, 10, y+18);
  });
}

// --- initial load
loadTasks();
