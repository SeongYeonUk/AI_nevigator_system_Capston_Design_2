const STORAGE_KEYS = {
  users: "pathlearn_users",
  session: "pathlearn_session"
};

const state = {
  users: loadUsers(),
  currentUser: loadSession(),
  selectedNodeId: "root",
  nodes: [
    {
      id: "root",
      parentId: null,
      title: "학습 시작",
      userQuestion: "AI 학습 프로젝트를 어떻게 시작하면 좋을까?",
      aiAnswer: "목표를 정의한 뒤 대화를 트리로 시각화해 학습 경로를 관리하세요.",
      depth: 0,
      timestamp: Date.now() - 1000 * 60 * 8
    },
    {
      id: "n1",
      parentId: "root",
      title: "요구사항 분석",
      userQuestion: "핵심 기능을 먼저 정리해줘",
      aiAnswer: "실시간 노드 생성, 분기 대화, 경로 이탈 알림을 MVP로 잡는 것이 좋습니다.",
      depth: 1,
      timestamp: Date.now() - 1000 * 60 * 6
    },
    {
      id: "n2",
      parentId: "n1",
      title: "트리 렌더링",
      userQuestion: "트리는 어떤 방식으로 그릴까?",
      aiAnswer: "재귀 렌더링 + 계층 데이터 구조를 사용하면 유지보수가 쉬워집니다.",
      depth: 2,
      timestamp: Date.now() - 1000 * 60 * 4
    }
  ]
};

const el = {
  landingView: document.getElementById("landingView"),
  authView: document.getElementById("authView"),
  appView: document.getElementById("appView"),
  openLoginBtn: document.getElementById("openLoginBtn"),
  openSignupBtn: document.getElementById("openSignupBtn"),
  logoutBtn: document.getElementById("logoutBtn"),
  heroStartBtn: document.getElementById("heroStartBtn"),
  heroDemoBtn: document.getElementById("heroDemoBtn"),
  tabLogin: document.getElementById("tabLogin"),
  tabSignup: document.getElementById("tabSignup"),
  loginForm: document.getElementById("loginForm"),
  signupForm: document.getElementById("signupForm"),
  authMsg: document.getElementById("authMsg"),
  treeRoot: document.getElementById("treeRoot"),
  nodeCount: document.getElementById("nodeCount"),
  branchTag: document.getElementById("branchTag"),
  chatFeed: document.getElementById("chatFeed"),
  chatForm: document.getElementById("chatForm"),
  chatInput: document.getElementById("chatInput"),
  selectedNodeTitle: document.getElementById("selectedNodeTitle"),
  selectedNodeMeta: document.getElementById("selectedNodeMeta"),
  depthBar: document.getElementById("depthBar"),
  driftAlert: document.getElementById("driftAlert")
};

bindEvents();
render();

function bindEvents() {
  el.openLoginBtn.addEventListener("click", () => switchView("auth", "login"));
  el.openSignupBtn.addEventListener("click", () => switchView("auth", "signup"));
  el.heroStartBtn.addEventListener("click", onHeroStartClick);
  el.heroDemoBtn.addEventListener("click", () => {
    switchView("auth", "login");
  });
  el.logoutBtn.addEventListener("click", logout);

  el.tabLogin.addEventListener("click", () => toggleAuthTab("login"));
  el.tabSignup.addEventListener("click", () => toggleAuthTab("signup"));

  el.loginForm.addEventListener("submit", onLogin);
  el.signupForm.addEventListener("submit", onSignup);
  el.chatForm.addEventListener("submit", onSendMessage);
}

function onHeroStartClick() {
  if (state.currentUser) {
    return;
  }

  const shouldMoveToLogin = confirm("로그인을 하셔야 합니다. 로그인 창으로 이동하시겠습니까?");
  if (shouldMoveToLogin) {
    switchView("auth", "login");
  }
}

function render() {
  const isLoggedIn = Boolean(state.currentUser);

  if (isLoggedIn) {
    switchView("landing");
  } else {
    switchView("landing");
  }

  renderTree();
  renderChat();
  renderInsights();
}

function switchView(view, authTab) {
  el.landingView.classList.toggle("hidden", view !== "landing");
  el.authView.classList.toggle("hidden", view !== "auth");
  el.appView.classList.toggle("hidden", view !== "app");

  const isLoggedIn = Boolean(state.currentUser);
  el.logoutBtn.classList.toggle("hidden", !isLoggedIn);
  el.openLoginBtn.classList.toggle("hidden", isLoggedIn);
  el.openSignupBtn.classList.toggle("hidden", isLoggedIn);

  if (view === "auth") {
    toggleAuthTab(authTab || "login");
  }
}

function toggleAuthTab(tab) {
  const loginActive = tab === "login";
  el.tabLogin.classList.toggle("active", loginActive);
  el.tabSignup.classList.toggle("active", !loginActive);
  el.loginForm.classList.toggle("active", loginActive);
  el.signupForm.classList.toggle("active", !loginActive);
  setAuthMessage("");
}

function onSignup(event) {
  event.preventDefault();
  const formData = new FormData(el.signupForm);
  const username = String(formData.get("username") || "").trim();
  const displayName = String(formData.get("displayName") || "").trim();
  const password = String(formData.get("password") || "");
  const confirmPassword = String(formData.get("confirmPassword") || "");
  const hasLetter = /[A-Za-z]/.test(password);
  const hasNumber = /\d/.test(password);

  if (!hasLetter || !hasNumber) {
    const message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다.";
    setAuthMessage(message, "error");
    alert(message);
    return;
  }

  if (password !== confirmPassword) {
    setAuthMessage("비밀번호와 비밀번호 확인이 일치하지 않습니다.", "error");
    return;
  }

  if (state.users.some((u) => u.username === username)) {
    setAuthMessage("이미 존재하는 아이디입니다.", "error");
    return;
  }

  state.users.push({ username, displayName, password });
  saveUsers(state.users);
  setAuthMessage("회원가입이 완료되었습니다. 로그인해 주세요.", "success");
  el.signupForm.reset();
  toggleAuthTab("login");
}

function onLogin(event) {
  event.preventDefault();
  const formData = new FormData(el.loginForm);
  const username = String(formData.get("username") || "").trim();
  const password = String(formData.get("password") || "");

  const user = state.users.find((u) => u.username === username && u.password === password);
  if (!user) {
    setAuthMessage("아이디 또는 비밀번호가 올바르지 않습니다.", "error");
    return;
  }

  state.currentUser = {
    username: user.username,
    displayName: user.displayName
  };
  saveSession(state.currentUser);
  el.loginForm.reset();
  render();
}

function logout() {
  state.currentUser = null;
  clearSession();
  switchView("landing");
}

function setAuthMessage(msg, type = "") {
  el.authMsg.textContent = msg;
  el.authMsg.className = "message";
  if (type) {
    el.authMsg.classList.add(type);
  }
}

function enterDemoMode() {
  state.currentUser = { username: "guest", displayName: "Demo User" };
  render();
}

function renderTree() {
  el.treeRoot.innerHTML = "";
  const roots = buildTree(state.nodes).filter((node) => node.parentId === null);
  roots.forEach((node) => el.treeRoot.appendChild(renderTreeNode(node)));
  el.nodeCount.textContent = `${state.nodes.length} Nodes`;
}

function renderTreeNode(node) {
  const wrapper = document.createElement("div");
  wrapper.className = "tree-node";

  const btn = document.createElement("button");
  btn.className = "node-btn";
  if (node.id === state.selectedNodeId) {
    btn.classList.add("active");
  }

  const time = new Date(node.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  btn.innerHTML = `<span class="node-title">${node.title}</span><span class="node-meta">Depth ${node.depth} · ${time}</span>`;
  btn.addEventListener("click", () => {
    state.selectedNodeId = node.id;
    render();
  });
  wrapper.appendChild(btn);

  if (node.children.length > 0) {
    const childrenWrap = document.createElement("div");
    childrenWrap.className = "node-children";
    node.children.forEach((child) => childrenWrap.appendChild(renderTreeNode(child)));
    wrapper.appendChild(childrenWrap);
  }

  return wrapper;
}

function renderChat() {
  el.chatFeed.innerHTML = "";

  const pathNodes = getPathToNode(state.selectedNodeId);
  pathNodes.forEach((node) => {
    el.chatFeed.appendChild(makeBubble("user", node.userQuestion, node.timestamp));
    el.chatFeed.appendChild(makeBubble("ai", node.aiAnswer, node.timestamp + 1000));
  });

  const selected = getNodeById(state.selectedNodeId);
  el.branchTag.textContent = selected ? `Branch from: ${selected.title}` : "Root Branch";
  el.chatFeed.scrollTop = el.chatFeed.scrollHeight;
}

function renderInsights() {
  const node = getNodeById(state.selectedNodeId);
  if (!node) {
    return;
  }

  const depth = node.depth;
  const maxDepthForAlert = 7;
  const ratio = Math.min(100, Math.round((depth / maxDepthForAlert) * 100));

  el.selectedNodeTitle.textContent = node.title;
  el.selectedNodeMeta.textContent = `Depth ${node.depth} · Parent ${node.parentId || "없음"}`;
  el.depthBar.style.width = `${Math.max(10, ratio)}%`;

  const isWarn = depth >= 5;
  if (isWarn) {
    el.depthBar.style.background = "linear-gradient(90deg, #f7d16a, #ff8d7a)";
    el.driftAlert.textContent = "주의: 현재 경로가 깊어졌습니다. 루트 목표와의 연관성을 다시 확인하세요.";
    el.driftAlert.classList.add("warn");
  } else {
    el.depthBar.style.background = "linear-gradient(90deg, #43dab8, #62b4d8)";
    el.driftAlert.textContent = "정상 범위: 학습 경로가 안정적으로 유지되고 있습니다.";
    el.driftAlert.classList.remove("warn");
  }
}

function onSendMessage(event) {
  event.preventDefault();
  const question = el.chatInput.value.trim();
  if (!question) {
    return;
  }

  const parent = getNodeById(state.selectedNodeId);
  const nextId = `n${Date.now().toString(36)}`;
  const nextDepth = parent ? parent.depth + 1 : 0;

  const node = {
    id: nextId,
    parentId: parent ? parent.id : null,
    title: summarizeTitle(question),
    userQuestion: question,
    aiAnswer: createMockAnswer(question, nextDepth),
    depth: nextDepth,
    timestamp: Date.now()
  };

  state.nodes.push(node);
  state.selectedNodeId = nextId;
  el.chatInput.value = "";
  render();
}

function makeBubble(role, text, timestamp) {
  const bubble = document.createElement("div");
  bubble.className = `bubble ${role}`;
  const time = new Date(timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  bubble.innerHTML = `${escapeHtml(text)}<span class="time">${role === "user" ? "You" : "AI"} · ${time}</span>`;
  return bubble;
}

function summarizeTitle(text) {
  const clean = text.replace(/[?.,!]/g, "").trim();
  if (clean.length <= 8) {
    return clean;
  }
  return `${clean.slice(0, 8)}...`;
}

function createMockAnswer(question, depth) {
  const snippets = [
    "해당 질문은 학습 목표와 연결되는 핵심 개념부터 정리하는 것이 좋습니다.",
    "현재 선택된 맥락을 기준으로 하위 주제를 분기해 비교 학습을 진행해 보세요.",
    "다음 단계에서는 실험 가능한 액션 아이템과 검증 기준을 함께 정의하세요.",
    "이 질문은 이전 노드와의 연관성이 높아 같은 브랜치에서 확장하는 것이 효율적입니다."
  ];

  const index = (question.length + depth) % snippets.length;
  return snippets[index];
}

function getNodeById(id) {
  return state.nodes.find((node) => node.id === id) || null;
}

function getPathToNode(nodeId) {
  const nodeMap = new Map(state.nodes.map((node) => [node.id, node]));
  const path = [];
  let cursor = nodeMap.get(nodeId);

  while (cursor) {
    path.unshift(cursor);
    cursor = cursor.parentId ? nodeMap.get(cursor.parentId) : null;
  }

  return path;
}

function buildTree(nodes) {
  const map = new Map(nodes.map((node) => [node.id, { ...node, children: [] }]));

  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) {
      map.get(node.parentId).children.push(node);
    }
  });

  return [...map.values()];
}

function escapeHtml(value) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function loadUsers() {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.users);
    return raw ? JSON.parse(raw) : [];
  } catch (err) {
    return [];
  }
}

function saveUsers(users) {
  localStorage.setItem(STORAGE_KEYS.users, JSON.stringify(users));
}

function loadSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.session);
    return raw ? JSON.parse(raw) : null;
  } catch (err) {
    return null;
  }
}

function saveSession(session) {
  localStorage.setItem(STORAGE_KEYS.session, JSON.stringify(session));
}

function clearSession() {
  localStorage.removeItem(STORAGE_KEYS.session);
}
