import { loginApi, signupApi } from "./api/auth-api.js";
import {
  createRoomApi,
  getRoomHistoryApi,
  getRoomsApi,
  requestAssistantTurn,
  updateRoomTitleApi
} from "./api/chat-api.js";
import { deleteAccountApi, getProfileApi, updateProfileApi } from "./api/user-api.js";
import { CHAT_API_MODE } from "./config.js";
import { clearSession, loadSession, saveSession } from "./state/session-store.js";

const state = {
  currentSession: loadSession(),
  currentView: "landing",
  treeViewMode: "list",
  selectedNodeId: null,
  nodes: [],
  chatRooms: [],
  currentRoomId: null,
  isRoomDrawerOpen: false
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
  openRoomsBtn: document.getElementById("openRoomsBtn"),
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

  roomDrawerToggle: document.getElementById("roomDrawerToggle"),
  roomDrawerCloseBtn: document.getElementById("roomDrawerCloseBtn"),
  roomDrawerBackdrop: document.getElementById("roomDrawerBackdrop"),
  roomDrawer: document.getElementById("roomDrawer"),
  roomCreateForm: document.getElementById("roomCreateForm"),
  roomTitleInput: document.getElementById("roomTitleInput"),
  roomList: document.getElementById("roomList"),

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
  el.openLoginBtn?.addEventListener("click", () => switchView("auth", "login"));
  el.openSignupBtn?.addEventListener("click", () => switchView("auth", "signup"));
  el.openHomeBtn?.addEventListener("click", () => switchView("landing"));
  el.openSettingsBtn?.addEventListener("click", openSettingsView);
  el.openRoomsBtn?.addEventListener("click", async () => {
    if (!state.currentSession?.accessToken) {
      switchView("auth", "login");
      return;
    }
    if (state.currentView !== "app") {
      await openAppView();
    }
    toggleRoomDrawer(true);
  });
  el.heroStartBtn?.addEventListener("click", onHeroStartClick);
  el.heroDemoBtn?.addEventListener("click", onHeroDemoClick);
  el.logoutBtn?.addEventListener("click", logout);

  el.tabLogin?.addEventListener("click", () => toggleAuthTab("login"));
  el.tabSignup?.addEventListener("click", () => toggleAuthTab("signup"));
  el.settingsBackBtn?.addEventListener("click", () => openAppView());

  el.loginForm?.addEventListener("submit", onLogin);
  el.signupForm?.addEventListener("submit", onSignup);
  el.profileForm?.addEventListener("submit", onUpdateProfile);
  el.deleteAccountForm?.addEventListener("submit", onDeleteAccount);
  el.chatForm?.addEventListener("submit", onSendMessage);

  el.treeListModeBtn?.addEventListener("click", () => setTreeViewMode("list"));
  el.treeGraphModeBtn?.addEventListener("click", () => setTreeViewMode("graph"));

  el.roomDrawerToggle?.addEventListener("click", () => toggleRoomDrawer());
  el.roomDrawerCloseBtn?.addEventListener("click", () => toggleRoomDrawer(false));
  el.roomDrawerBackdrop?.addEventListener("click", () => toggleRoomDrawer(false));
  el.roomCreateForm?.addEventListener("submit", onCreateRoom);
}

function render() {
  switchView(state.currentView);
  renderRoomDrawer();
  renderTree();
  renderChat();
  renderInsights();
  syncChatInputAvailability();
}

function switchView(view, authTab) {
  state.currentView = view;

  el.landingView?.classList.toggle("hidden", view !== "landing");
  el.authView?.classList.toggle("hidden", view !== "auth");
  el.appView?.classList.toggle("hidden", view !== "app");
  el.settingsView?.classList.toggle("hidden", view !== "settings");

  const isLoggedIn = Boolean(state.currentSession?.accessToken);
  el.logoutBtn?.classList.toggle("hidden", !isLoggedIn);
  el.openLoginBtn?.classList.toggle("hidden", isLoggedIn);
  el.openSignupBtn?.classList.toggle("hidden", isLoggedIn);
  el.openHomeBtn?.classList.toggle("hidden", !isLoggedIn);
  el.openSettingsBtn?.classList.toggle("hidden", !isLoggedIn);
  el.openRoomsBtn?.classList.toggle("hidden", !isLoggedIn);

  if (view === "auth") {
    toggleAuthTab(authTab || "login");
  }

  syncChatInputAvailability();
}

function toggleAuthTab(tab) {
  const loginActive = tab === "login";
  el.tabLogin?.classList.toggle("active", loginActive);
  el.tabSignup?.classList.toggle("active", !loginActive);
  el.loginForm?.classList.toggle("active", loginActive);
  el.signupForm?.classList.toggle("active", !loginActive);
  setAuthMessage("");
}

function setTreeViewMode(mode) {
  if (mode !== "list" && mode !== "graph") {
    return;
  }
  state.treeViewMode = mode;
  renderTree();
}

async function onHeroStartClick() {
  if (!state.currentSession?.accessToken) {
    const shouldMoveToLogin = confirm("로그인을 하셔야 합니다. 로그인 창으로 이동하시겠습니까?");
    if (shouldMoveToLogin) {
      switchView("auth", "login");
    }
    return;
  }

  await openAppView();
}

async function onHeroDemoClick() {
  if (state.currentSession?.accessToken) {
    await openAppView();
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

  if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) {
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
    await signupApi({ loginId: username, password, nickname: displayName });
    setAuthMessage("회원가입이 완료되었습니다. 로그인해 주세요.", "success");
    el.signupForm?.reset();
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
    const response = await loginApi({ loginId: username, password });
    state.currentSession = { loginId: username, accessToken: response.accessToken };
    await syncProfileFromServer();
    saveSession(state.currentSession);
    el.loginForm?.reset();
    setAuthMessage("");
    switchView("landing");
  } catch (error) {
    setAuthMessage(toUiError(error), "error");
  }
}

async function openAppView() {
  if (!state.currentSession?.accessToken) {
    switchView("auth", "login");
    return;
  }

  toggleRoomDrawer(false);
  switchView("app");
  await bootstrapChatRooms();
  render();
}

async function bootstrapChatRooms() {
  try {
    const rooms = await getRoomsApi(state.currentSession?.accessToken || "");
    state.chatRooms = Array.isArray(rooms) ? rooms.sort((a, b) => Number(b.id) - Number(a.id)) : [];

    if (state.chatRooms.length === 0) {
      state.currentRoomId = null;
      state.nodes = [];
      state.selectedNodeId = null;
      return;
    }

    if (!state.currentRoomId || !state.chatRooms.some((room) => room.id === state.currentRoomId)) {
      state.currentRoomId = state.chatRooms[0].id;
    }

    await loadRoomHistory(state.currentRoomId);
  } catch (error) {
    state.currentRoomId = null;
    state.nodes = [];
    state.selectedNodeId = null;
    setAuthMessage(toUiError(error), "error");
  }
}

function toggleRoomDrawer(force) {
  if (typeof force === "boolean") {
    state.isRoomDrawerOpen = force;
  } else {
    state.isRoomDrawerOpen = !state.isRoomDrawerOpen;
  }
  renderRoomDrawer();
}

function renderRoomDrawer() {
  if (!el.roomDrawer) {
    return;
  }

  el.roomDrawer.classList.toggle("open", state.isRoomDrawerOpen);
  el.roomDrawerBackdrop?.classList.toggle("open", state.isRoomDrawerOpen);
  el.appView?.classList.toggle("drawer-open", state.isRoomDrawerOpen);

  if (!el.roomList) {
    return;
  }

  el.roomList.innerHTML = "";
  state.chatRooms.forEach((room) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = `room-item ${room.id === state.currentRoomId ? "active" : ""}`;
    btn.innerHTML = `<span class="title">${escapeHtml(room.title || "새 대화")}</span><span class="meta">#${room.id}</span>`;
    btn.addEventListener("click", async () => {
      state.currentRoomId = room.id;
      await loadRoomHistory(room.id);
      toggleRoomDrawer(false);
      render();
    });
    el.roomList.appendChild(btn);
  });
}

async function onCreateRoom(event) {
  event.preventDefault();
  try {
    const roomId = await createRoomWithFallbackTitle();
    state.currentRoomId = roomId;
    await bootstrapChatRooms();
    if (el.roomTitleInput) {
      el.roomTitleInput.value = "";
    }
    render();
  } catch (error) {
    alert(toUiError(error));
  }
}

async function createRoomWithFallbackTitle() {
  const inputTitle = (el.roomTitleInput?.value || "").trim();
  const fallback = `대화 ${new Date().toLocaleString()}`;
  const roomId = await createRoomApi(inputTitle || fallback, state.currentSession?.accessToken || "");
  return Number(roomId);
}

async function loadRoomHistory(roomId) {
  const history = await getRoomHistoryApi(roomId, state.currentSession?.accessToken || "");
  state.nodes = historyToNodes(history, roomId);
  state.selectedNodeId = state.nodes.length ? state.nodes[state.nodes.length - 1].id : null;
}

function historyToNodes(history, roomId) {
  const nodes = [];
  if (!Array.isArray(history) || history.length === 0) {
    return nodes;
  }

  let parentId = null;
  let pendingUser = null;

  history.forEach((entry, idx) => {
    const sender = String(entry.sender || "").toUpperCase();
    const content = String(entry.content || "").trim();
    const ts = entry.createdAt ? Date.parse(entry.createdAt) : Date.now() + idx;

    if (!content) {
      return;
    }

    if (sender === "USER") {
      pendingUser = { content, ts };
      return;
    }

    if (sender === "AI") {
      const question = pendingUser?.content || "(이전 질문 없음)";
      const qTs = pendingUser?.ts || ts;
      const depth = parentId ? (nodes.find((n) => n.id === parentId)?.depth ?? -1) + 1 : 0;
      const id = `n_${roomIdSafe(roomId)}_${idx}`;

      nodes.push({
        id,
        parentId,
        title: summarizeTitle(question),
        userQuestion: question,
        aiAnswer: content,
        depth,
        timestamp: qTs
      });

      parentId = id;
      pendingUser = null;
    }
  });

  return nodes;
}

function roomIdSafe(roomId) {
  return roomId == null ? "none" : String(roomId).replace(/[^a-zA-Z0-9_-]/g, "");
}

async function onSendMessage(event) {
  event.preventDefault();

  if (!state.currentSession?.accessToken) {
    const shouldMoveToLogin = confirm("로그인을 하셔야 합니다. 로그인 창으로 이동하시겠습니까?");
    if (shouldMoveToLogin) {
      switchView("auth", "login");
    }
    return;
  }

  const question = (el.chatInput?.value || "").trim();
  if (!question) {
    return;
  }

  try {
    if (!state.currentRoomId) {
      state.currentRoomId = await createRoomWithFallbackTitle();
      await bootstrapChatRooms();
    }

    if (CHAT_API_MODE === "backend" && !Number.isFinite(Number(state.currentRoomId))) {
      throw new Error("채팅방 생성에 실패했습니다. 채팅목록에서 새 방을 먼저 만들어 주세요.");
    }

    const parent = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
    const nextDepth = parent ? parent.depth + 1 : 0;
    const nextId = `n_${Date.now().toString(36)}`;
    const now = Date.now();

    // 전송 클릭 즉시 UI 반영
    state.nodes.push({
      id: nextId,
      parentId: parent ? parent.id : null,
      title: summarizeTitle(question),
      userQuestion: question,
      aiAnswer: "응답 생성 중...",
      depth: nextDepth,
      timestamp: now
    });
    state.selectedNodeId = nextId;
    if (el.chatInput) {
      el.chatInput.value = "";
    }
    render();

    const currentRoom = state.chatRooms.find((room) => room.id === state.currentRoomId);
    if (currentRoom && isAutoGeneratedRoomTitle(currentRoom.title)) {
      const nextTitle = summarizeRoomTitle(question);
      try {
        await updateRoomTitleApi(state.currentRoomId, nextTitle, state.currentSession?.accessToken || "");
        currentRoom.title = nextTitle;
      } catch {
        // ignore title update failure
      }
    }

    const response = await requestAssistantTurn({
      roomId: state.currentRoomId,
      message: question,
      depth: nextDepth,
      token: state.currentSession?.accessToken || ""
    });
    const target = state.nodes.find((n) => n.id === nextId);
    if (target) {
      target.aiAnswer = response.answer || "응답이 없습니다.";
    }

    try {
      await refreshRoomsOnly();
    } catch {
      // backend rooms sync failed; keep local flow
    }

    setAuthMessage("");
    render();
  } catch (error) {
    const target = state.nodes.find((n) => n.id === state.selectedNodeId);
    if (target && target.aiAnswer === "응답 생성 중...") {
      target.aiAnswer = `응답 실패: ${toUiError(error)}`;
    }
    setAuthMessage(`전송 실패: ${toUiError(error)}`, "error");
    render();
  }
}

async function refreshRoomsOnly() {
  const rooms = await getRoomsApi(state.currentSession?.accessToken || "");
  state.chatRooms = Array.isArray(rooms) ? rooms.sort((a, b) => Number(b.id) - Number(a.id)) : [];
}

async function openSettingsView() {
  if (!state.currentSession?.accessToken) {
    switchView("auth", "login");
    return;
  }

  await syncProfileFromServer();
  if (el.profileLoginId) {
    el.profileLoginId.value = state.currentSession?.loginId || "";
  }
  if (state.currentSession?.nickname && el.profileForm?.elements?.nickname) {
    el.profileForm.elements.nickname.value = state.currentSession.nickname;
  }
  if (el.profileForm?.elements?.currentPassword) {
    el.profileForm.elements.currentPassword.value = "";
  }
  if (el.profileForm?.elements?.newPassword) {
    el.profileForm.elements.newPassword.value = "";
  }
  el.deleteAccountForm?.reset();
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
    await updateProfileApi(state.currentSession.accessToken, { nickname, currentPassword, newPassword });
    state.currentSession.nickname = nickname;
    saveSession(state.currentSession);
    setSettingsMessage("회원정보가 수정되었습니다.", "success");
    if (el.profileForm?.elements?.currentPassword) {
      el.profileForm.elements.currentPassword.value = "";
    }
    if (el.profileForm?.elements?.newPassword) {
      el.profileForm.elements.newPassword.value = "";
    }
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
    state.chatRooms = [];
    state.currentRoomId = null;
    state.nodes = [];
    state.selectedNodeId = null;
    setSettingsMessage("");
    switchView("landing");
    render();
  } catch (error) {
    setSettingsMessage(toUiError(error), "error");
  }
}

function logout() {
  state.currentSession = null;
  state.chatRooms = [];
  state.currentRoomId = null;
  state.nodes = [];
  state.selectedNodeId = null;
  clearSession();
  switchView("landing");
  render();
}

function renderTree() {
  if (!el.treeRoot) {
    return;
  }

  el.treeRoot.innerHTML = "";
  el.treeListModeBtn?.classList.toggle("active", state.treeViewMode === "list");
  el.treeGraphModeBtn?.classList.toggle("active", state.treeViewMode === "graph");
  el.treeRoot.classList.toggle("graph-mode", state.treeViewMode === "graph");

  if (state.nodes.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "질문을 보내면 첫 노드가 생성됩니다.";
    el.treeRoot.appendChild(empty);
    if (el.nodeCount) {
      el.nodeCount.textContent = "0 Nodes";
    }
    return;
  }

  if (state.treeViewMode === "graph") {
    renderTreeGraph();
  } else {
    renderTreeList();
  }

  if (el.nodeCount) {
    el.nodeCount.textContent = `${state.nodes.length} Nodes`;
  }
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
  if (!el.chatFeed) {
    return;
  }

  el.chatFeed.innerHTML = "";

  const pathNodes = state.selectedNodeId ? getPathToNode(state.selectedNodeId) : [];
  pathNodes.forEach((node) => {
    el.chatFeed.appendChild(makeBubble("user", node.userQuestion, node.timestamp));
    el.chatFeed.appendChild(makeBubble("ai", node.aiAnswer, node.timestamp + 1000));
  });

  if (pathNodes.length === 0) {
    el.chatFeed.appendChild(makeBubble("ai", "질문을 입력하면 첫 노드가 생성됩니다.", Date.now()));
  }

  const room = state.chatRooms.find((r) => r.id === state.currentRoomId);
  const selected = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
  const roomLabel = room ? `Room: ${room.title}` : "Room: 선택 없음";
  if (el.branchTag) {
    el.branchTag.textContent = selected ? `${roomLabel} / ${selected.title}` : roomLabel;
  }
  el.chatFeed.scrollTop = el.chatFeed.scrollHeight;
}

function renderInsights() {
  const node = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
  if (!node) {
    if (el.selectedNodeTitle) {
      el.selectedNodeTitle.textContent = "선택 없음";
    }
    if (el.selectedNodeMeta) {
      el.selectedNodeMeta.textContent = "Depth - / Parent -";
    }
    if (el.depthBar) {
      el.depthBar.style.width = "10%";
      el.depthBar.style.background = "linear-gradient(90deg, #43dab8, #62b4d8)";
    }
    if (el.driftAlert) {
      el.driftAlert.textContent = "질문을 입력하면 학습 경로가 시작됩니다.";
      el.driftAlert.classList.remove("warn");
    }
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
    min: 260,
    max: 760,
    direction: "right"
  });

  setupSingleResizer({
    handle: el.insightResizeHandle,
    panel: el.insightPanel,
    cssVar: "--insight-panel-width",
    min: 260,
    max: 760,
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

function summarizeRoomTitle(text) {
  const clean = String(text || "").replace(/\s+/g, " ").trim();
  if (!clean) {
    return "새 대화";
  }
  return clean.length <= 24 ? clean : `${clean.slice(0, 24)}...`;
}

function isAutoGeneratedRoomTitle(title) {
  const value = String(title || "").trim();
  if (!value) {
    return true;
  }
  if (value === "새 대화") {
    return true;
  }
  return value.startsWith("대화 ");
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
  if (!el.authMsg) {
    return;
  }
  el.authMsg.textContent = message;
  el.authMsg.className = "message";
  if (type) {
    el.authMsg.classList.add(type);
  }
}

function setSettingsMessage(message, type = "") {
  if (!el.settingsMsg) {
    return;
  }
  el.settingsMsg.textContent = message;
  el.settingsMsg.className = "message";
  if (type) {
    el.settingsMsg.classList.add(type);
  }
}

function syncChatInputAvailability() {
  const canChat = Boolean(state.currentSession?.accessToken);
  if (!el.chatInput) {
    return;
  }

  el.chatInput.disabled = !canChat;
  const submitButton = el.chatForm?.querySelector("button[type='submit']");
  if (submitButton) {
    submitButton.disabled = !canChat;
  }

  el.chatInput.placeholder = canChat
    ? "질문을 입력하면 현재 선택 노드에서 분기됩니다."
    : "로그인 후 질문을 입력할 수 있습니다.";
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
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");
}



