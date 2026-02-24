import { loginApi, signupApi } from "./api/auth-api.js";
import { requestAssistantTurn } from "./api/chat-api.js";
import { deleteAccountApi, getProfileApi, updateProfileApi } from "./api/user-api.js";
import { clearSession, loadSession, saveSession } from "./state/session-store.js";

const state = {
  currentSession: loadSession(),
  currentView: loadSession() ? "app" : "landing",
  treeViewMode: "list",
  selectedNodeId: "root",
  nodes: createInitialNodes()
};

const el = {
  landingView: document.getElementById("landingView"),
  authView: document.getElementById("authView"),
  appView: document.getElementById("appView"),
  settingsView: document.getElementById("settingsView"),
  openLoginBtn: document.getElementById("openLoginBtn"),
  openSignupBtn: document.getElementById("openSignupBtn"),
  openHomeBtn: document.getElementById("openHomeBtn"),
  openSettingsBtn: document.getElementById("openSettingsBtn"),
  logoutBtn: document.getElementById("logoutBtn"),
  heroStartBtn: document.getElementById("heroStartBtn"),
  heroDemoBtn: document.getElementById("heroDemoBtn"),
  tabLogin: document.getElementById("tabLogin"),
  tabSignup: document.getElementById("tabSignup"),
  settingsBackBtn: document.getElementById("settingsBackBtn"),
  loginForm: document.getElementById("loginForm"),
  signupForm: document.getElementById("signupForm"),
  profileForm: document.getElementById("profileForm"),
  deleteAccountForm: document.getElementById("deleteAccountForm"),
  profileLoginId: document.getElementById("profileLoginId"),
  authMsg: document.getElementById("authMsg"),
  settingsMsg: document.getElementById("settingsMsg"),
  treeRoot: document.getElementById("treeRoot"),
  treeListModeBtn: document.getElementById("treeListModeBtn"),
  treeGraphModeBtn: document.getElementById("treeGraphModeBtn"),
  nodeCount: document.getElementById("nodeCount"),
  branchTag: document.getElementById("branchTag"),
  chatFeed: document.getElementById("chatFeed"),
  chatForm: document.getElementById("chatForm"),
  chatInput: document.getElementById("chatInput"),
  selectedNodeTitle: document.getElementById("selectedNodeTitle"),
  selectedNodeMeta: document.getElementById("selectedNodeMeta"),
  depthBar: document.getElementById("depthBar"),
  driftAlert: document.getElementById("driftAlert"),
  treeResizeHandle: document.getElementById("treeResizeHandle"),
  insightResizeHandle: document.getElementById("insightResizeHandle"),
  treePanel: document.querySelector(".tree-panel"),
  insightPanel: document.querySelector(".insight-panel")
};

bindEvents();
setupPanelResizers();
render();

function bindEvents() {
  el.openLoginBtn.addEventListener("click", () => switchView("auth", "login"));
  el.openSignupBtn.addEventListener("click", () => switchView("auth", "signup"));
  el.openHomeBtn.addEventListener("click", () => switchView("landing"));
  el.openSettingsBtn.addEventListener("click", openSettingsView);
  el.heroStartBtn.addEventListener("click", onHeroStartClick);
  el.heroDemoBtn.addEventListener("click", onHeroDemoClick);
  el.logoutBtn.addEventListener("click", logout);

  el.tabLogin.addEventListener("click", () => toggleAuthTab("login"));
  el.tabSignup.addEventListener("click", () => toggleAuthTab("signup"));
  el.settingsBackBtn.addEventListener("click", () => switchView("app"));
  el.loginForm.addEventListener("submit", onLogin);
  el.signupForm.addEventListener("submit", onSignup);
  el.profileForm.addEventListener("submit", onUpdateProfile);
  el.deleteAccountForm.addEventListener("submit", onDeleteAccount);
  el.chatForm.addEventListener("submit", onSendMessage);
  el.treeListModeBtn.addEventListener("click", () => setTreeViewMode("list"));
  el.treeGraphModeBtn.addEventListener("click", () => setTreeViewMode("graph"));
}

function render() {
  switchView(state.currentView);
  renderTree();
  renderChat();
  renderInsights();
}

function switchView(view, authTab) {
  state.currentView = view;

  el.landingView.classList.toggle("hidden", view !== "landing");
  el.authView.classList.toggle("hidden", view !== "auth");
  el.appView.classList.toggle("hidden", view !== "app");
  el.settingsView.classList.toggle("hidden", view !== "settings");

  const isLoggedIn = Boolean(state.currentSession?.accessToken);
  el.logoutBtn.classList.toggle("hidden", !isLoggedIn);
  el.openLoginBtn.classList.toggle("hidden", isLoggedIn);
  el.openSignupBtn.classList.toggle("hidden", isLoggedIn);
  el.openHomeBtn.classList.toggle("hidden", !isLoggedIn);
  el.openSettingsBtn.classList.toggle("hidden", !isLoggedIn);

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

function setTreeViewMode(mode) {
  if (mode !== "list" && mode !== "graph") {
    return;
  }
  state.treeViewMode = mode;
  renderTree();
}

function onHeroStartClick() {
  if (state.currentSession?.accessToken) {
    switchView("app");
    render();
    return;
  }

  const shouldMoveToLogin = confirm("로그인을 하셔야 합니다. 로그인 창으로 이동하시겠습니까?");
  if (shouldMoveToLogin) {
    switchView("auth", "login");
  }
}

function onHeroDemoClick() {
  if (state.currentSession?.accessToken) {
    switchView("app");
    render();
    return;
  }
  switchView("auth", "login");
}

async function onSignup(event) {
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

  try {
    await signupApi({
      loginId: username,
      password,
      nickname: displayName
    });
    setAuthMessage("회원가입이 완료되었습니다. 로그인해 주세요.", "success");
    el.signupForm.reset();
    toggleAuthTab("login");
  } catch (error) {
    setAuthMessage(toUiError(error), "error");
  }
}

async function onLogin(event) {
  event.preventDefault();

  const formData = new FormData(el.loginForm);
  const username = String(formData.get("username") || "").trim();
  const password = String(formData.get("password") || "");

  try {
    const response = await loginApi({
      loginId: username,
      password
    });

    state.currentSession = {
      loginId: username,
      accessToken: response.accessToken
    };

    await syncProfileFromServer();
    saveSession(state.currentSession);
    el.loginForm.reset();
    setAuthMessage("");
    switchView("app");
    render();
  } catch (error) {
    setAuthMessage(toUiError(error), "error");
  }
}

async function openSettingsView() {
  await syncProfileFromServer();
  el.profileLoginId.value = state.currentSession?.loginId || "";
  if (state.currentSession?.nickname) {
    el.profileForm.elements.nickname.value = state.currentSession.nickname;
  }
  el.profileForm.elements.currentPassword.value = "";
  el.profileForm.elements.newPassword.value = "";
  el.deleteAccountForm.reset();
  setSettingsMessage("");
  switchView("settings");
}

async function onUpdateProfile(event) {
  event.preventDefault();

  const formData = new FormData(el.profileForm);
  const nickname = String(formData.get("nickname") || "").trim();
  const currentPassword = String(formData.get("currentPassword") || "");
  const newPassword = String(formData.get("newPassword") || "");

  if (!nickname) {
    setSettingsMessage("닉네임을 입력해 주세요.", "error");
    return;
  }

  if (newPassword) {
    const hasLetter = /[A-Za-z]/.test(newPassword);
    const hasNumber = /\d/.test(newPassword);
    if (!hasLetter || !hasNumber) {
      setSettingsMessage("새 비밀번호는 영문과 숫자를 모두 포함해야 합니다.", "error");
      return;
    }
    if (!currentPassword) {
      setSettingsMessage("비밀번호를 변경하려면 현재 비밀번호가 필요합니다.", "error");
      return;
    }
  }

  try {
    await updateProfileApi(state.currentSession.accessToken, {
      nickname,
      currentPassword,
      newPassword
    });

    state.currentSession.nickname = nickname;
    saveSession(state.currentSession);
    setSettingsMessage("회원정보가 수정되었습니다.", "success");
    el.profileForm.elements.currentPassword.value = "";
    el.profileForm.elements.newPassword.value = "";
  } catch (error) {
    setSettingsMessage(toUiError(error), "error");
  }
}

async function onDeleteAccount(event) {
  event.preventDefault();

  const formData = new FormData(el.deleteAccountForm);
  const password = String(formData.get("password") || "");
  if (!password) {
    setSettingsMessage("탈퇴 확인을 위해 비밀번호를 입력해 주세요.", "error");
    return;
  }

  const shouldDelete = confirm("정말 회원 탈퇴하시겠습니까? 이 작업은 되돌릴 수 없습니다.");
  if (!shouldDelete) {
    return;
  }

  try {
    await deleteAccountApi(state.currentSession.accessToken, password);
    state.currentSession = null;
    clearSession();
    setSettingsMessage("");
    switchView("landing");
  } catch (error) {
    setSettingsMessage(toUiError(error), "error");
  }
}

function logout() {
  state.currentSession = null;
  clearSession();
  switchView("landing");
}

async function onSendMessage(event) {
  event.preventDefault();

  const question = el.chatInput.value.trim();
  if (!question) {
    return;
  }

  const parent = getNodeById(state.selectedNodeId);
  const nextId = `n${Date.now().toString(36)}`;
  const nextDepth = parent ? parent.depth + 1 : 0;

  try {
    const assistant = await requestAssistantTurn({
      message: question,
      depth: nextDepth,
      parentNodeId: parent ? parent.id : null,
      token: state.currentSession?.accessToken || ""
    });

    state.nodes.push({
      id: nextId,
      parentId: parent ? parent.id : null,
      title: summarizeTitle(question),
      userQuestion: question,
      aiAnswer: assistant.answer || "응답이 없습니다.",
      depth: nextDepth,
      timestamp: Date.now()
    });

    state.selectedNodeId = nextId;
    el.chatInput.value = "";
    render();
  } catch (error) {
    setAuthMessage(toUiError(error), "error");
  }
}

function renderTree() {
  el.treeRoot.innerHTML = "";
  el.treeListModeBtn.classList.toggle("active", state.treeViewMode === "list");
  el.treeGraphModeBtn.classList.toggle("active", state.treeViewMode === "graph");
  el.treeRoot.classList.toggle("graph-mode", state.treeViewMode === "graph");

  if (state.treeViewMode === "graph") {
    renderTreeGraph();
  } else {
    renderTreeList();
  }

  el.nodeCount.textContent = `${state.nodes.length} Nodes`;
}

function renderTreeList() {
  const roots = buildTree(state.nodes).filter((node) => node.parentId === null);
  roots.forEach((node) => el.treeRoot.appendChild(renderTreeNode(node)));
}

function renderTreeGraph() {
  const graph = getTreeGraphLayout(state.nodes);
  const svgNS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("class", "tree-graph-svg");
  svg.setAttribute("viewBox", `0 0 ${graph.width} ${graph.height}`);
  svg.setAttribute("preserveAspectRatio", "xMidYMin meet");

  graph.links.forEach((link) => {
    const line = document.createElementNS(svgNS, "line");
    line.setAttribute("x1", String(link.x1));
    line.setAttribute("y1", String(link.y1));
    line.setAttribute("x2", String(link.x2));
    line.setAttribute("y2", String(link.y2));
    line.setAttribute("class", "tree-link");
    svg.appendChild(line);
  });

  graph.nodes.forEach((node) => {
    const circle = document.createElementNS(svgNS, "circle");
    circle.setAttribute("cx", String(node.x));
    circle.setAttribute("cy", String(node.y));
    circle.setAttribute("r", "14");
    circle.setAttribute("class", node.id === state.selectedNodeId ? "tree-node-circle active" : "tree-node-circle");
    circle.addEventListener("click", () => {
      state.selectedNodeId = node.id;
      render();
    });
    svg.appendChild(circle);

    const label = document.createElementNS(svgNS, "text");
    label.setAttribute("x", String(node.x));
    label.setAttribute("y", String(node.y + 4));
    label.setAttribute("class", "tree-node-label");
    label.textContent = node.title.length > 6 ? `${node.title.slice(0, 6)}...` : node.title;
    svg.appendChild(label);
  });

  el.treeRoot.appendChild(svg);
}

function renderTreeNode(node) {
  const wrapper = document.createElement("div");
  wrapper.className = "tree-node";

  const button = document.createElement("button");
  button.className = "node-btn";
  if (node.id === state.selectedNodeId) {
    button.classList.add("active");
  }

  const time = new Date(node.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  button.innerHTML = `<span class="node-title">${escapeHtml(node.title)}</span><span class="node-meta">Depth ${node.depth} / ${time}</span>`;
  button.addEventListener("click", () => {
    state.selectedNodeId = node.id;
    render();
  });
  wrapper.appendChild(button);

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
  const ratio = Math.min(100, Math.round((depth / 7) * 100));

  el.selectedNodeTitle.textContent = node.title;
  el.selectedNodeMeta.textContent = `Depth ${node.depth} / Parent ${node.parentId || "없음"}`;
  el.depthBar.style.width = `${Math.max(10, ratio)}%`;

  if (depth >= 5) {
    el.depthBar.style.background = "linear-gradient(90deg, #f7d16a, #ff8d7a)";
    el.driftAlert.textContent = "주의: 경로 깊이가 높습니다. 목표와의 정합성을 다시 확인하세요.";
    el.driftAlert.classList.add("warn");
  } else {
    el.depthBar.style.background = "linear-gradient(90deg, #43dab8, #62b4d8)";
    el.driftAlert.textContent = "정상 범위: 학습 경로가 안정적으로 유지되고 있습니다.";
    el.driftAlert.classList.remove("warn");
  }
}

function setupPanelResizers() {
  setupSingleResizer({
    handle: el.treeResizeHandle,
    panel: el.treePanel,
    cssVar: "--tree-panel-width",
    min: 220,
    max: 620,
    direction: "right"
  });

  setupSingleResizer({
    handle: el.insightResizeHandle,
    panel: el.insightPanel,
    cssVar: "--insight-panel-width",
    min: 240,
    max: 560,
    direction: "left"
  });
}

function setupSingleResizer({ handle, panel, cssVar, min, max, direction }) {
  if (!handle || !panel) {
    return;
  }

  let dragging = false;
  let startX = 0;
  let startWidth = 0;

  const onMove = (event) => {
    if (!dragging) {
      return;
    }
    const clientX = event.clientX ?? startX;
    const delta = clientX - startX;
    const signedDelta = direction === "left" ? -delta : delta;
    const nextWidth = clamp(startWidth + signedDelta, min, max);
    document.documentElement.style.setProperty(cssVar, `${nextWidth}px`);
  };

  const onUp = () => {
    dragging = false;
    handle.classList.remove("dragging");
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  };

  handle.addEventListener("pointerdown", (event) => {
    dragging = true;
    startX = event.clientX;
    startWidth = panel.getBoundingClientRect().width;
    handle.classList.add("dragging");
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });
}

function makeBubble(role, text, timestamp) {
  const bubble = document.createElement("div");
  bubble.className = `bubble ${role}`;
  const time = new Date(timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  bubble.innerHTML = `${escapeHtml(text)}<span class="time">${role === "user" ? "You" : "AI"} / ${time}</span>`;
  return bubble;
}

function summarizeTitle(text) {
  const clean = text.replace(/[?.,!]/g, "").trim();
  return clean.length <= 10 ? clean : `${clean.slice(0, 10)}...`;
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

function getTreeGraphLayout(nodes) {
  const tree = buildTree(nodes);
  const roots = tree.filter((node) => node.parentId === null);
  const xGap = 72;
  const yGap = 74;
  const margin = 30;
  let cursorX = 0;

  function assign(node, depth, placed) {
    if (node.children.length === 0) {
      const x = cursorX;
      cursorX += 1;
      const p = { id: node.id, title: node.title, depth, x };
      placed.push(p);
      return p;
    }

    const childPlaced = node.children.map((child) => assign(child, depth + 1, placed));
    const avgX = childPlaced.reduce((sum, child) => sum + child.x, 0) / childPlaced.length;
    const p = { id: node.id, title: node.title, depth, x: avgX };
    placed.push(p);
    return p;
  }

  const placed = [];
  roots.forEach((root) => {
    assign(root, 0, placed);
    cursorX += 1;
  });

  const maxDepth = Math.max(...placed.map((p) => p.depth), 0);
  const graphNodes = placed.map((p) => ({
    ...p,
    x: margin + p.x * xGap,
    y: margin + p.depth * yGap
  }));

  const graphNodeMap = new Map(graphNodes.map((p) => [p.id, p]));

  const links = [];
  tree.forEach((node) => {
    if (!node.children.length) {
      return;
    }
    const parent = graphNodeMap.get(node.id);
    node.children.forEach((child) => {
      const target = graphNodeMap.get(child.id);
      if (parent && target) {
        links.push({
          x1: parent.x,
          y1: parent.y + 14,
          x2: target.x,
          y2: target.y - 14
        });
      }
    });
  });

  const maxX = Math.max(...graphNodes.map((p) => p.x), margin);
  const minX = Math.min(...graphNodes.map((p) => p.x), margin);
  const contentWidth = maxX - minX;
  const minCanvasWidth = 320;
  const width = Math.max(minCanvasWidth, maxX + margin);
  const shiftX = (width - (contentWidth + margin * 2)) / 2;

  if (shiftX > 0) {
    graphNodes.forEach((node) => {
      node.x += shiftX;
    });
    links.forEach((link) => {
      link.x1 += shiftX;
      link.x2 += shiftX;
    });
  }

  return {
    nodes: graphNodes,
    links,
    width,
    height: margin * 2 + maxDepth * yGap + 26
  };
}

function setAuthMessage(message, type = "") {
  el.authMsg.textContent = message;
  el.authMsg.className = "message";
  if (type) {
    el.authMsg.classList.add(type);
  }
}

function setSettingsMessage(message, type = "") {
  el.settingsMsg.textContent = message;
  el.settingsMsg.className = "message";
  if (type) {
    el.settingsMsg.classList.add(type);
  }
}

async function syncProfileFromServer() {
  if (!state.currentSession?.accessToken) {
    return;
  }

  try {
    const profile = await getProfileApi(state.currentSession.accessToken);
    if (profile && typeof profile === "object") {
      state.currentSession.loginId = profile.loginId || state.currentSession.loginId;
      state.currentSession.nickname = profile.nickname || state.currentSession.nickname || "";
      saveSession(state.currentSession);
    }
  } catch (error) {
    // ignore
  }
}

function toUiError(error) {
  const message = error instanceof Error ? error.message : "";
  if (!message) {
    return "요청 처리 중 오류가 발생했습니다.";
  }
  if (message.includes("Failed to fetch")) {
    return "백엔드 연결에 실패했습니다. 백엔드 실행 및 CORS 설정을 확인해 주세요.";
  }
  return message;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function escapeHtml(value) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function createInitialNodes() {
  const now = Date.now();
  return [
    {
      id: "root",
      parentId: null,
      title: "학습 시작",
      userQuestion: "AI 학습 프로젝트를 어떻게 시작하면 좋을까?",
      aiAnswer: "목표를 정의한 뒤 대화를 트리로 시각화해 학습 경로를 관리하세요.",
      depth: 0,
      timestamp: now - 1000 * 60 * 16
    },
    {
      id: "n1-a",
      parentId: "root",
      title: "요구사항 A",
      userQuestion: "핵심 기능 쪽 요구사항부터 보자.",
      aiAnswer: "로그인, 회원가입, 설정 화면을 우선순위로 정리하면 좋습니다.",
      depth: 1,
      timestamp: now - 1000 * 60 * 14
    },
    {
      id: "n1-b",
      parentId: "root",
      title: "요구사항 B",
      userQuestion: "대화 분기와 트리 가시화 요구사항도 보자.",
      aiAnswer: "분기 추적, 그래프 표시, 선택 노드 동기화를 포함하세요.",
      depth: 1,
      timestamp: now - 1000 * 60 * 13
    },
    {
      id: "n2-a1",
      parentId: "n1-a",
      title: "인증 UI",
      userQuestion: "인증 화면의 컴포넌트 경계를 어떻게 잡지?",
      aiAnswer: "폼 상태, API 호출, 메시지 표시를 분리하면 유지보수가 쉬워집니다.",
      depth: 2,
      timestamp: now - 1000 * 60 * 11
    },
    {
      id: "n2-a2",
      parentId: "n1-a",
      title: "계정 설정",
      userQuestion: "설정 화면에서 제공할 기능을 정리해줘.",
      aiAnswer: "닉네임 수정, 비밀번호 변경, 회원탈퇴를 기본 기능으로 두세요.",
      depth: 2,
      timestamp: now - 1000 * 60 * 10
    },
    {
      id: "n2-b1",
      parentId: "n1-b",
      title: "트리 상호작용",
      userQuestion: "트리에서 노드 선택/분기를 어떻게 자연스럽게 보여줄까?",
      aiAnswer: "활성 노드 강조, 부모-자식 연결선, 타임스탬프 표시를 같이 주면 좋습니다.",
      depth: 2,
      timestamp: now - 1000 * 60 * 9
    },
    {
      id: "n3-b1",
      parentId: "n2-b1",
      title: "그래프 최적화",
      userQuestion: "그래프형이 너무 커지지 않게 만들려면?",
      aiAnswer: "노드 반경/간격 최소값을 두고 캔버스 폭을 보정해 과확대를 막으세요.",
      depth: 3,
      timestamp: now - 1000 * 60 * 8
    }
  ];
}
