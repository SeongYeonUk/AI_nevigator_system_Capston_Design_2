import { loginApi, signupApi } from "./api/auth-api.js";
import {
  askChatApi,
  createRoomApi,
  deleteRoomApi,
  getNodeInsightApi,
  getRoomHistoryApi,
  getRoomTreeApi,
  getRoomsApi,
  updateRoomTitleApi
} from "./api/chat-api.js";
import { deleteAccountApi, getProfileApi, updateProfileApi } from "./api/user-api.js";
import { CHAT_API_MODE } from "./config.js";
import { clearSession, loadSession, saveSession } from "./state/session-store.js";

if (typeof window !== "undefined") {
  window.__PATHLEARN_MAIN_READY = false;
}

let transparentDragImage = null;

const state = {
  currentSession: loadSession(),
  currentView: "landing",
  treeViewMode: "list",
  graphZoom: 1,
  roomDeleteMode: false,
  selectedRoomIdsForDelete: new Set(),
  suppressNodeClick: false,
  selectedNodeId: null,
  nodes: [],
  treeNodes: [],
  pendingTreeMutations: [],
  chatRooms: [],
  currentRoomId: null,
  isRoomDrawerOpen: false,
  treeBuildStatus: "completed",
  pendingTreeBuildJobs: 0,
  treeProcessingWatcherToken: 0,
  insightCache: new Map(),
  pendingInsightKeys: new Set(),
  insightRequestToken: 0,
  dragState: {
    sourceNodeId: null,
    targetNodeId: null,
    active: false,
    previewElement: null,
    previewFollowsPointer: false,
    grabOffsetX: 0,
    grabOffsetY: 0,
    pointerX: 0,
    pointerY: 0
  }
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
  roomDeleteModeBtn: document.getElementById("roomDeleteModeBtn"),
  roomDeleteApplyBtn: document.getElementById("roomDeleteApplyBtn"),
  roomDeleteCancelBtn: document.getElementById("roomDeleteCancelBtn"),

  authMsg: document.getElementById("authMsg"),
  settingsMsg: document.getElementById("settingsMsg"),

  treeRoot: document.getElementById("treeRoot"),
  treeListModeBtn: document.getElementById("treeListModeBtn"),
  treeGraphModeBtn: document.getElementById("treeGraphModeBtn"),
  nodeCount: document.getElementById("nodeCount"),
  graphZoomInBtn: document.getElementById("graphZoomInBtn"),
  graphZoomOutBtn: document.getElementById("graphZoomOutBtn"),
  graphZoomResetBtn: document.getElementById("graphZoomResetBtn"),
  graphZoomLevel: document.getElementById("graphZoomLevel"),
  graphZoomFooter: document.getElementById("graphZoomFooter"),
  treeBuildStatus: document.getElementById("treeBuildStatus"),

  branchTag: document.getElementById("branchTag"),
  chatFeed: document.getElementById("chatFeed"),
  chatForm: document.getElementById("chatForm"),
  chatInput: document.getElementById("chatInput"),

  selectedNodeTitle: document.getElementById("selectedNodeTitle"),
  selectedNodeMeta: document.getElementById("selectedNodeMeta"),
  deleteSelectedNodeBtn: document.getElementById("deleteSelectedNodeBtn"),
  treeEditHint: document.getElementById("treeEditHint"),
  depthBar: document.getElementById("depthBar"),
  driftAlert: document.getElementById("driftAlert"),
  conversationSummaryList: document.getElementById("conversationSummaryList"),

  treeResizeHandle: document.getElementById("treeResizeHandle"),
  insightResizeHandle: document.getElementById("insightResizeHandle"),
  treePanel: document.querySelector(".tree-panel"),
  insightPanel: document.querySelector(".insight-panel")
};

bindEvents();
setupPanelResizers();
render();
if (typeof window !== "undefined") {
  window.__PATHLEARN_MAIN_READY = true;
}

function bindEvents() {
  el.openLoginBtn?.addEventListener("click", () => {
    switchView("auth", "login");
    render();
  });
  el.openSignupBtn?.addEventListener("click", () => {
    switchView("auth", "signup");
    render();
  });
  el.openHomeBtn?.addEventListener("click", () => {
    switchView("landing");
    render();
  });
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
  el.graphZoomInBtn?.addEventListener("click", () => changeGraphZoom(0.2));
  el.graphZoomOutBtn?.addEventListener("click", () => changeGraphZoom(-0.2));
  el.graphZoomResetBtn?.addEventListener("click", resetGraphZoom);

  el.roomDrawerToggle?.addEventListener("click", () => toggleRoomDrawer());
  el.roomDrawerCloseBtn?.addEventListener("click", () => toggleRoomDrawer(false));
  el.roomDrawerBackdrop?.addEventListener("click", () => toggleRoomDrawer(false));
  el.roomCreateForm?.addEventListener("submit", onCreateRoom);
  el.roomDeleteModeBtn?.addEventListener("click", enterRoomDeleteMode);
  el.roomDeleteApplyBtn?.addEventListener("click", onApplyDeleteSelectedRooms);
  el.roomDeleteCancelBtn?.addEventListener("click", exitRoomDeleteMode);
  el.deleteSelectedNodeBtn?.addEventListener("click", onDeleteSelectedNode);
  document.addEventListener("dragover", onDocumentTreeDragOver);
}

function render() {
  switchView(state.currentView);
  renderRoomDrawer();
  renderTree();
  renderChat();
  renderInsights();
  syncChatInputAvailability();
}

function cloneNode(node) {
  return { ...node };
}

function getTreeSourceNodes() {
  return state.treeNodes.length ? state.treeNodes : state.nodes;
}

function clearTreeDragState() {
  if (state.dragState.previewElement?.remove) {
    state.dragState.previewElement.remove();
  }
  document.body.classList.remove("tree-dragging");
  state.dragState = {
    sourceNodeId: null,
    targetNodeId: null,
    active: false,
    previewElement: null,
    previewFollowsPointer: false,
    grabOffsetX: 0,
    grabOffsetY: 0,
    pointerX: 0,
    pointerY: 0
  };
}

function isNodeInDraggedSubtree(nodeId) {
  if (!state.dragState.active || !state.dragState.sourceNodeId) {
    return false;
  }
  return collectSubtreeIds(state.dragState.sourceNodeId, getTreeSourceNodes()).has(String(nodeId));
}

function getTreeNodeSubtree(nodeId, nodes = getTreeSourceNodes()) {
  const tree = buildTree(nodes);
  return tree.find((node) => node.id === String(nodeId)) || null;
}

function getDraggedSubtreeNodes(nodeId, nodes = getTreeSourceNodes()) {
  const subtreeIds = collectSubtreeIds(nodeId, nodes);
  return nodes.filter((node) => subtreeIds.has(String(node.id)));
}

function renderTreeDragPreviewNode(node) {
  const wrapper = document.createElement("div");
  wrapper.className = "tree-drag-preview-node";

  const card = document.createElement("div");
  card.className = "tree-drag-preview-card";

  const time = new Date(node.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  card.innerHTML = `<span class="tree-drag-preview-title">${escapeHtml(node.title)}</span><span class="tree-drag-preview-meta">Depth ${node.depth} / ${time}</span>`;
  wrapper.appendChild(card);

  if (node.children.length > 0) {
    const childrenWrap = document.createElement("div");
    childrenWrap.className = "tree-drag-preview-children";
    node.children.forEach((child) => childrenWrap.appendChild(renderTreeDragPreviewNode(child)));
    wrapper.appendChild(childrenWrap);
  }

  return wrapper;
}

function createGraphTreeDragPreview(nodeId) {
  const subtreeIds = collectSubtreeIds(nodeId, getTreeSourceNodes());
  const sourceSvg = el.treeRoot?.querySelector(".tree-graph-svg");
  if (!sourceSvg || !subtreeIds.size) {
    return null;
  }

  const nodeGroups = [...sourceSvg.querySelectorAll(".tree-node-group[data-node-id]")]
    .filter((group) => subtreeIds.has(group.dataset.nodeId));
  if (!nodeGroups.length) {
    return null;
  }

  const relevantLinks = [...sourceSvg.querySelectorAll(".tree-link[data-source-id][data-target-id]")]
    .filter((link) => subtreeIds.has(link.dataset.sourceId) && subtreeIds.has(link.dataset.targetId));

  const bounds = nodeGroups.reduce((acc, group) => {
    const circle = group.querySelector("circle");
    const label = group.querySelector("text");
    const cx = Number(circle?.getAttribute("cx")) || 0;
    const cy = Number(circle?.getAttribute("cy")) || 0;
    const r = Number(circle?.getAttribute("r")) || 20;
    const textWidth = Math.max(44, String(label?.textContent || "").length * 8);
    acc.minX = Math.min(acc.minX, cx - Math.max(r, textWidth / 2));
    acc.maxX = Math.max(acc.maxX, cx + Math.max(r, textWidth / 2));
    acc.minY = Math.min(acc.minY, cy - r - 6);
    acc.maxY = Math.max(acc.maxY, cy + r + 12);
    return acc;
  }, { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity });

  const sourceGroup = nodeGroups.find((group) => group.dataset.nodeId === String(nodeId)) || nodeGroups[0];
  const sourceCircle = sourceGroup?.querySelector("circle");

  const padding = 14;
  const minX = bounds.minX - padding;
  const minY = bounds.minY - padding;
  const normalizedWidth = Math.max(80, (bounds.maxX - bounds.minX) + padding * 2);
  const normalizedHeight = Math.max(80, (bounds.maxY - bounds.minY) + padding * 2);
  const preview = document.createElement("div");
  preview.className = "tree-drag-preview graph-preview";
  const svgNS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("class", "tree-drag-preview-graph");
  svg.setAttribute("viewBox", `0 0 ${normalizedWidth} ${normalizedHeight}`);
  svg.setAttribute("width", String(normalizedWidth));
  svg.setAttribute("height", String(normalizedHeight));

  relevantLinks.forEach((link) => {
    const clone = link.cloneNode(true);
    clone.setAttribute("x1", String((Number(link.getAttribute("x1")) || 0) - minX));
    clone.setAttribute("y1", String((Number(link.getAttribute("y1")) || 0) - minY));
    clone.setAttribute("x2", String((Number(link.getAttribute("x2")) || 0) - minX));
    clone.setAttribute("y2", String((Number(link.getAttribute("y2")) || 0) - minY));
    clone.setAttribute("class", "tree-drag-preview-link");
    svg.appendChild(clone);
  });

  nodeGroups.forEach((group) => {
    const clone = group.cloneNode(true);
    clone.setAttribute("class", "tree-node-group");
    clone.querySelectorAll("circle").forEach((circle) => {
      circle.setAttribute("cx", String((Number(circle.getAttribute("cx")) || 0) - minX));
      circle.setAttribute("cy", String((Number(circle.getAttribute("cy")) || 0) - minY));
      circle.setAttribute("class", "tree-drag-preview-node-circle");
    });
    clone.querySelectorAll("text").forEach((label) => {
      label.setAttribute("x", String((Number(label.getAttribute("x")) || 0) - minX));
      label.setAttribute("y", String((Number(label.getAttribute("y")) || 0) - minY));
      label.setAttribute("class", "tree-drag-preview-node-label");
      label.style.pointerEvents = "none";
    });
    svg.appendChild(clone);
  });

  preview.appendChild(svg);
  document.body.appendChild(preview);
  if (sourceCircle) {
    preview.dataset.anchorX = String((Number(sourceCircle.getAttribute("cx")) || 0) - minX);
    preview.dataset.anchorY = String((Number(sourceCircle.getAttribute("cy")) || 0) - minY);
  }
  return preview;
}

function createTreeDragPreview(nodeId) {
  if (state.treeViewMode === "graph") {
    return createGraphTreeDragPreview(nodeId);
  }

  const preview = document.createElement("div");
  preview.className = "tree-drag-preview list-preview";
  const subtree = getTreeNodeSubtree(nodeId);
  if (!subtree) {
    return null;
  }
  preview.appendChild(renderTreeDragPreviewNode(subtree));
  document.body.appendChild(preview);
  return preview;
}

function updateTreeDragPreviewPosition(clientX = state.dragState.pointerX, clientY = state.dragState.pointerY) {
  const { previewElement } = state.dragState;
  if (!previewElement || !state.dragState.previewFollowsPointer || (!clientX && !clientY)) {
    return;
  }
  previewElement.style.transform = `translate(${clientX - state.dragState.grabOffsetX}px, ${clientY - state.dragState.grabOffsetY}px)`;
}

function updateTreeDragPointer(clientX, clientY) {
  if (!state.dragState.active) {
    return;
  }
  if (Number.isFinite(clientX)) {
    state.dragState.pointerX = clientX;
  }
  if (Number.isFinite(clientY)) {
    state.dragState.pointerY = clientY;
  }
  updateTreeDragPreviewPosition();
}

function ensureTreeDragPreview(nodeId) {
  if (!state.dragState.active || !nodeId || state.dragState.previewElement) {
    return state.dragState.previewElement;
  }
  const preview = createTreeDragPreview(nodeId);
  state.dragState.previewElement = preview;
  updateTreeDragPreviewPosition();
  return preview;
}

function getTransparentDragImage() {
  if (transparentDragImage) {
    return transparentDragImage;
  }
  const pixel = document.createElement("canvas");
  pixel.width = 1;
  pixel.height = 1;
  transparentDragImage = pixel;
  return transparentDragImage;
}

function clearPendingTreeMutations() {
  state.pendingTreeMutations = [];
  clearTreeDragState();
}

function switchView(view, authTab) {
  state.currentView = view;
  document.body.classList.toggle("app-active", view === "app");

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
      render();
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
  render();
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
    const visibleRooms = Array.isArray(rooms) ? rooms : [];
    state.chatRooms = visibleRooms.sort((a, b) => Number(b.id) - Number(a.id));

    if (state.chatRooms.length === 0) {
      state.currentRoomId = null;
      state.nodes = [];
      state.treeNodes = [];
      state.selectedNodeId = null;
      state.treeBuildStatus = "completed";
      state.treeProcessingWatcherToken++;
      return;
    }

    if (!state.currentRoomId || !state.chatRooms.some((room) => room.id === state.currentRoomId)) {
      clearPendingTreeMutations();
      state.currentRoomId = state.chatRooms[0].id;
    }

    await loadRoomHistory(state.currentRoomId);
  } catch (error) {
    state.currentRoomId = null;
    state.nodes = [];
    state.treeNodes = [];
    clearPendingTreeMutations();
    state.selectedNodeId = null;
    state.treeBuildStatus = "completed";
    state.treeProcessingWatcherToken++;
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

  el.roomDeleteModeBtn?.classList.toggle("hidden", state.roomDeleteMode);
  el.roomDeleteApplyBtn?.classList.toggle("hidden", !state.roomDeleteMode);
  el.roomDeleteCancelBtn?.classList.toggle("hidden", !state.roomDeleteMode);

  if (!el.roomList) {
    return;
  }

  el.roomList.innerHTML = "";
  state.chatRooms.forEach((room) => {
    const row = document.createElement("div");
    row.className = "room-item-row";

    if (state.roomDeleteMode) {
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.className = "room-select-checkbox";
      checkbox.checked = state.selectedRoomIdsForDelete.has(String(room.id));
      checkbox.addEventListener("change", (event) => {
        const roomKey = String(room.id);
        if (event.target.checked) {
          state.selectedRoomIdsForDelete.add(roomKey);
        } else {
          state.selectedRoomIdsForDelete.delete(roomKey);
        }
      });
      row.appendChild(checkbox);
    }

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = `room-item ${room.id === state.currentRoomId ? "active" : ""}`;
    btn.innerHTML = `<span class="title">${escapeHtml(room.title || "새 대화")}</span><span class="meta">#${room.id}</span>`;
    btn.addEventListener("click", async () => {
      if (state.roomDeleteMode) {
        return;
      }
      clearPendingTreeMutations();
      state.treeProcessingWatcherToken++;
      state.currentRoomId = room.id;
      await loadRoomHistory(room.id);
      toggleRoomDrawer(false);
      render();
    });

    row.appendChild(btn);
    el.roomList.appendChild(row);
  });
}

function enterRoomDeleteMode() {
  state.roomDeleteMode = true;
  state.selectedRoomIdsForDelete.clear();
  renderRoomDrawer();
}

function exitRoomDeleteMode() {
  state.roomDeleteMode = false;
  state.selectedRoomIdsForDelete.clear();
  renderRoomDrawer();
}

async function onApplyDeleteSelectedRooms() {
  if (state.selectedRoomIdsForDelete.size === 0) {
    alert("삭제할 대화를 먼저 선택해 주세요.");
    return;
  }

  const ok = confirm(`선택한 ${state.selectedRoomIdsForDelete.size}개의 대화를 삭제하시겠습니까?`);
  if (!ok) {
    return;
  }

  for (const roomIdKey of state.selectedRoomIdsForDelete) {
    await deleteRoomApi(roomIdKey, state.currentSession?.accessToken || "");
  }
  state.chatRooms = state.chatRooms.filter((room) => !state.selectedRoomIdsForDelete.has(String(room.id)));

  if (state.currentRoomId && state.selectedRoomIdsForDelete.has(String(state.currentRoomId))) {
    clearPendingTreeMutations();
    state.currentRoomId = state.chatRooms.length ? state.chatRooms[0].id : null;
    if (state.currentRoomId) {
      await loadRoomHistory(state.currentRoomId);
    } else {
      state.nodes = [];
      state.treeNodes = [];
      clearPendingTreeMutations();
      state.selectedNodeId = null;
      state.treeBuildStatus = "completed";
      state.treeProcessingWatcherToken++;
    }
  }

  state.roomDeleteMode = false;
  state.selectedRoomIdsForDelete.clear();
  render();
}

async function onCreateRoom(event) {
  event.preventDefault();
  try {
    const roomId = await createRoomWithFallbackTitle();
    clearPendingTreeMutations();
    state.treeProcessingWatcherToken++;
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
  return loadRoomHistoryWithOptions(roomId, {});
}

async function loadRoomHistoryWithOptions(roomId, options = {}) {
  const token = state.currentSession?.accessToken || "";
  const keepTreeWhileProcessing = options.keepTreeWhileProcessing === true;
  const suppressWatcher = options.suppressWatcher === true;

  try {
    const tree = await getRoomTreeApi(roomId, token);
    if (state.currentRoomId !== roomId) {
      return false;
    }
    state.treeBuildStatus = tree?.processing ? "processing" : "completed";
    if (tree && Array.isArray(tree.nodes)) {
      const nextNodes = applyPendingTreeMutationsTo(treeToNodes(tree.nodes));
      state.nodes = nextNodes;
      if (!keepTreeWhileProcessing || !tree.processing) {
        state.treeNodes = nextNodes;
      }
      if (tree.processing && !suppressWatcher) {
        startTreeProcessingWatcher(roomId);
      }
      state.selectedNodeId = state.nodes.length ? state.nodes[state.nodes.length - 1].id : null;
      return true;
    }
  } catch (error) {
    console.warn("Tree API fallback to history API:", error);
  }

  const history = await getRoomHistoryApi(roomId, token);
  if (state.currentRoomId !== roomId) {
    return false;
  }
  state.treeBuildStatus = "completed";
  state.nodes = applyPendingTreeMutationsTo(historyToNodes(history, roomId));
  state.treeNodes = state.nodes;
  state.selectedNodeId = state.nodes.length ? state.nodes[state.nodes.length - 1].id : null;
  return true;
}

async function waitForTreeProcessingToFinish(roomId, targetNodeId) {
  const timeoutMs = 30000;
  const intervalMs = 700;
  const startedAt = Date.now();

  while (Date.now() - startedAt < timeoutMs) {
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    const applied = await loadRoomHistoryWithOptions(roomId, { keepTreeWhileProcessing: true });
    if (!applied || state.currentRoomId !== roomId) {
      return false;
    }
    if (state.treeBuildStatus !== "processing") {
      state.treeNodes = state.nodes;
      if (targetNodeId && state.nodes.some((node) => node.id === targetNodeId)) {
        state.selectedNodeId = targetNodeId;
      }
      render();
      return true;
    }
  }

  const applied = await loadRoomHistoryWithOptions(roomId, { keepTreeWhileProcessing: false });
  if (applied && targetNodeId && state.nodes.some((node) => node.id === targetNodeId)) {
    state.selectedNodeId = targetNodeId;
  }
  render();
  return applied;
}

function startTreeProcessingWatcher(roomId, targetNodeId = null) {
  if (!roomId || state.currentRoomId !== roomId) {
    return;
  }

  const watcherToken = ++state.treeProcessingWatcherToken;
  const startedAt = Date.now();
  const timeoutMs = 45000;
  const intervalMs = 900;

  const tick = async () => {
    if (state.currentRoomId !== roomId || state.treeProcessingWatcherToken !== watcherToken) {
      return;
    }

    const applied = await loadRoomHistoryWithOptions(roomId, {
      keepTreeWhileProcessing: true,
      suppressWatcher: true
    });

    if (!applied || state.currentRoomId !== roomId || state.treeProcessingWatcherToken !== watcherToken) {
      return;
    }

    if (state.treeBuildStatus === "processing" && Date.now() - startedAt < timeoutMs) {
      render();
      setTimeout(tick, intervalMs);
      return;
    }

    state.treeNodes = state.nodes;
    if (targetNodeId && state.nodes.some((node) => node.id === targetNodeId)) {
      state.selectedNodeId = targetNodeId;
    }
    state.treeBuildStatus = "completed";
    render();
  };

  void tick();
}

function treeToNodes(treeNodes) {
  if (!Array.isArray(treeNodes)) {
    return [];
  }

  return treeNodes.map((entry) => ({
    id: String(entry.id),
    parentId: entry.parentId != null ? String(entry.parentId) : null,
    title: entry.title || summarizeTitle(entry.userQuestion || ""),
    userQuestion: entry.userQuestion || "",
    aiAnswer: entry.aiAnswer || "",
    depth: Number(entry.depth) || 0,
    timestamp: Date.parse(entry.createdAt) || Date.now()
  }));
}

function applyPendingTreeMutationsTo(nodes) {
  let nextNodes = nodes.map(cloneNode);

  for (const mutation of state.pendingTreeMutations) {
    if (!mutation || !mutation.type) {
      continue;
    }

    if (mutation.type === "delete_subtree") {
      const ids = collectSubtreeIds(mutation.nodeId, nextNodes);
      nextNodes = nextNodes.filter((node) => !ids.has(node.id));
      continue;
    }

    if (mutation.type === "move_subtree") {
      const sourceNode = nextNodes.find((node) => node.id === mutation.nodeId);
      const targetNode = nextNodes.find((node) => node.id === mutation.newParentId);
      if (!sourceNode || !targetNode) {
        continue;
      }
      if (!canMoveNodeUnderTarget(mutation.nodeId, mutation.newParentId, nextNodes)) {
        continue;
      }
      sourceNode.parentId = mutation.newParentId;
      sourceNode.depth = (Number(targetNode.depth) || 0) + 1;
      normalizeSubtreeDepths(nextNodes, sourceNode.id);
    }
  }

  return nextNodes;
}

function historyToNodes(history, roomId) {
  const nodes = [];
  if (!Array.isArray(history) || history.length === 0) return nodes;

  const userToAiMap = {}; // 유저 메시지 ID에 대응하는 AI 메시지 ID 저장소
  let pendingUser = null;

  // 1. 먼저 모든 쌍을 찾아서 유저-AI 맵을 만듭니다.
  history.forEach((entry) => {
    const sender = String(entry.sender || "").toUpperCase();
    if (sender === "USER") {
      pendingUser = entry;
    } else if (sender === "AI" && pendingUser) {
      userToAiMap[pendingUser.id] = entry.id; // 예: { 81: 82, 83: 84, 85: 86 }
      pendingUser = null;
    }
  });

  // 2. 이제 진짜 노드를 생성합니다.
  pendingUser = null;
  history.forEach((entry) => {
    const sender = String(entry.sender || "").toUpperCase();
    
    if (sender === "USER") {
      pendingUser = entry; 
      return;
    }

    if (sender === "AI" && pendingUser) {
      // [핵심] 부모가 유저 메시지(81)라면, 그 유저에게 답변한 AI(82)를 부모 노드로 설정합니다.
      const realParentId = pendingUser.parentId ? userToAiMap[pendingUser.parentId] : null;

      nodes.push({
        id: String(entry.id), // ID는 항상 문자열로!
        parentId: realParentId ? String(realParentId) : null, // 부모도 문자열로!
        title: pendingUser.nodeTitle || summarizeTitle(pendingUser.content),
        userQuestion: pendingUser.content,
        aiAnswer: entry.content,
        depth: entry.depth,
        timestamp: Date.parse(entry.createdAt)
      });
      pendingUser = null;
    }
  });

  return nodes;
}

function roomIdSafe(roomId) {
  return roomId == null ? "none" : String(roomId).replace(/[^a-zA-Z0-9_-]/g, "");
}

function buildNodePlacementSignature(node) {
  if (!node) {
    return "";
  }
  const parentId = node.parentId == null ? "root" : String(node.parentId);
  const depth = Number.isFinite(Number(node.depth)) ? Number(node.depth) : 0;
  const title = String(node.title || "").trim();
  return `${String(node.id)}|${parentId}|${depth}|${title}`;
}

function looksLikeSeedBootstrapQuestion(text) {
  const raw = String(text || "");
  const normalized = raw.trim().toLowerCase();
  if (!normalized) {
    return false;
  }

  const hasMainTopicLabel =
    /\uB300\uC8FC\uC81C/.test(raw) ||
    /\b(main|major|top)\s*topic\b/i.test(normalized);

  const hasSubTopicLabel =
    /\uC18C\uC8FC\uC81C/.test(raw) ||
    /\bsub\s*topics?\b/i.test(normalized) ||
    /\blevel\s*2\b/i.test(normalized) ||
    /\b(second|child)\s*level\b/i.test(normalized);

  if (hasMainTopicLabel && hasSubTopicLabel) {
    return true;
  }

  if (/\uC18C\uC8FC\uC81C\s*[:=]/.test(raw) || /\bsub\s*topics?\s*[:=]/i.test(normalized)) {
    return true;
  }

  return false;
}

function applyAssistantResponseToTempNode({
  tempId,
  response,
  question,
  parentId,
  nextDepth
}) {
  const persistedId = response?.newNodeId ? String(response.newNodeId) : tempId;
  const resolvedParentId = response?.resolvedParentId != null
    ? String(response.resolvedParentId)
    : null;
  const effectiveParentId = resolvedParentId || parentId || null;
  const existingPersistedIndex = state.nodes.findIndex((node) => node.id === persistedId);
  const index = state.nodes.findIndex((node) => node.id === tempId);

  const nextNodeState = {
    id: persistedId,
    parentId: effectiveParentId,
    title: response?.nodeTitle || summarizeTitle(question),
    userQuestion: question,
    aiAnswer: response?.answer || "응답 생성 중...",
    depth: Number.isFinite(response?.depth) ? Number(response.depth) : nextDepth,
    timestamp: Date.now()
  };

  if (index < 0) {
    if (existingPersistedIndex >= 0) {
      state.nodes[existingPersistedIndex] = {
        ...state.nodes[existingPersistedIndex],
        ...nextNodeState
      };
    } else {
      state.nodes.push(nextNodeState);
    }
    state.selectedNodeId = persistedId;
    return persistedId;
  }

  state.nodes[index] = {
    ...state.nodes[index],
    ...nextNodeState,
    aiAnswer: response?.answer || state.nodes[index].aiAnswer
  };

  state.nodes.forEach((node) => {
    if (node.parentId === tempId) {
      node.parentId = persistedId;
    }
  });

  if (state.selectedNodeId === tempId) {
    state.selectedNodeId = persistedId;
  }
  return persistedId;
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

  let tempId = null;
  const previousSelectedNodeId = state.selectedNodeId;

  try {
    if (!state.currentRoomId) {
      const roomId = await createRoomWithFallbackTitle();
      state.currentRoomId = roomId;
      await refreshRoomsOnly();
    }

    const parent = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
    const nextDepth = parent ? parent.depth + 1 : 0;
    const parentId = parent ? parent.id : null;
    tempId = `n_${Date.now().toString(36)}`;

    state.nodes.push({
      id: tempId,
      parentId,
      title: summarizeTitle(question),
      userQuestion: question,
      aiAnswer: "응답 생성 중...",
      depth: nextDepth,
      timestamp: Date.now()
    });
    state.selectedNodeId = tempId;
    render();

    const response = await askChatApi({
      roomId: state.currentRoomId,
      message: question,
      parentId,
      token: state.currentSession?.accessToken || ""
    });

    if (el.chatInput) {
      el.chatInput.value = "";
    }

    const persistedNodeId = applyAssistantResponseToTempNode({
      tempId,
      response,
      question,
      parentId,
      nextDepth
    });
    const applied = await loadRoomHistoryWithOptions(state.currentRoomId, { keepTreeWhileProcessing: true });
    if (applied && persistedNodeId && state.nodes.some((node) => node.id === persistedNodeId)) {
      state.selectedNodeId = persistedNodeId;
    }
    render();
    if (state.treeBuildStatus === "processing") {
      startTreeProcessingWatcher(state.currentRoomId, persistedNodeId);
    } else if (applied) {
      state.treeNodes = state.nodes;
      render();
    }
  } catch (error) {
    if (tempId) {
      state.nodes = state.nodes.filter((node) => node.id !== tempId);
      state.selectedNodeId = previousSelectedNodeId;
    }
    setAuthMessage(`전송 실패: ${toUiError(error)}`, "error");
    render();
  }
}
async function refreshRoomsOnly() {
  const rooms = await getRoomsApi(state.currentSession?.accessToken || "");
  const visibleRooms = Array.isArray(rooms) ? rooms : [];
  state.chatRooms = visibleRooms.sort((a, b) => Number(b.id) - Number(a.id));
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
    state.treeNodes = [];
    clearPendingTreeMutations();
    state.selectedNodeId = null;
    state.treeBuildStatus = "completed";
    state.treeProcessingWatcherToken++;
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
  state.treeNodes = [];
  clearPendingTreeMutations();
  state.selectedNodeId = null;
  state.treeBuildStatus = "completed";
  state.treeProcessingWatcherToken++;
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
  el.graphZoomFooter?.classList.toggle("hidden", state.treeViewMode !== "graph");
  if (el.graphZoomLevel) {
    el.graphZoomLevel.textContent = `${Math.round(state.graphZoom * 100)}%`;
  }
  renderTreeBuildStatus();

  const treeNodes = state.treeNodes.length ? state.treeNodes : state.nodes;

  if (treeNodes.length === 0) {
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
    renderTreeGraph(treeNodes);
  } else {
    renderTreeList(treeNodes);
  }

  if (el.nodeCount) {
    el.nodeCount.textContent = `${treeNodes.length} Nodes`;
  }
}

function renderTreeBuildStatus() {
  if (!el.treeBuildStatus) {
    return;
  }

  const isProcessing = state.treeBuildStatus === "processing";
  el.treeBuildStatus.textContent = isProcessing ? "트리 구성 진행중..." : "트리 구성 완료됨";
  el.treeBuildStatus.classList.toggle("processing", isProcessing);
  el.treeBuildStatus.classList.toggle("completed", !isProcessing);
}

function renderTreeList(nodes = state.nodes) {
  const roots = buildTree(nodes).filter((node) => node.parentId === null);
  roots.forEach((node) => el.treeRoot.appendChild(renderTreeNode(node)));
}

function renderTreeGraph(nodes = state.nodes) {
  const graph = getTreeGraphLayout(nodes);
  const svgNS = "http://www.w3.org/2000/svg";
  const treeTooltip = getOrCreateTreeTooltip();
  const baseNodeRadius = 20;
  const hoverNodeRadius = 23;
  const selectedHoverNodeRadius = 25;
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("class", "tree-graph-svg");
  svg.setAttribute("viewBox", `0 0 ${graph.width} ${graph.height}`);
  svg.setAttribute("preserveAspectRatio", "xMidYMin meet");
  svg.style.width = `${Math.max(320, Math.round(graph.width * state.graphZoom))}px`;
  svg.style.height = `${Math.max(220, Math.round(graph.height * state.graphZoom))}px`;

  graph.links.forEach((link) => {
    const line = document.createElementNS(svgNS, "line");
    line.setAttribute("x1", String(link.x1));
    line.setAttribute("y1", String(link.y1));
    line.setAttribute("x2", String(link.x2));
    line.setAttribute("y2", String(link.y2));
    line.setAttribute("class", "tree-link");
    line.dataset.sourceId = String(link.sourceId);
    line.dataset.targetId = String(link.targetId);
    svg.appendChild(line);
  });

  graph.nodes.forEach((node) => {
    const nodeGroup = document.createElementNS(svgNS, "g");
    nodeGroup.setAttribute("class", buildGraphNodeGroupClass(node.id));
    nodeGroup.dataset.nodeId = String(node.id);

    const circle = document.createElementNS(svgNS, "circle");
    circle.setAttribute("cx", String(node.x));
    circle.setAttribute("cy", String(node.y));
    circle.setAttribute("r", String(baseNodeRadius));
    circle.setAttribute("class", node.id === state.selectedNodeId ? "tree-node-circle active" : "tree-node-circle");
    const emphasizeNode = () => {
      circle.classList.add("hovered");
      circle.setAttribute("r", String(node.id === state.selectedNodeId ? selectedHoverNodeRadius : hoverNodeRadius));
    };
    const normalizeNode = () => {
      circle.classList.remove("hovered");
      circle.setAttribute("r", String(baseNodeRadius));
    };
    attachGraphDragHandlers(nodeGroup, node.id);
    circle.addEventListener("click", () => {
      if (!state.dragState.active) {
        selectNode(node.id);
      }
    });
    circle.addEventListener("mouseenter", (event) => {
      emphasizeNode();
      handleTreeDragHover(node.id);
      showTreeTooltip(treeTooltip, node.title, event);
    });
    circle.addEventListener("mousemove", (event) => moveTreeTooltip(treeTooltip, event));
    circle.addEventListener("mouseleave", () => {
      normalizeNode();
      handleTreeDragHover(null);
      hideTreeTooltip(treeTooltip);
    });
    nodeGroup.appendChild(circle);

    const label = document.createElementNS(svgNS, "text");
    label.setAttribute("x", String(node.x));
    label.setAttribute("y", String(node.y + 5));
    label.setAttribute("class", "tree-node-label");
    label.textContent = node.title.length > 6 ? `${node.title.slice(0, 6)}...` : node.title;
    label.style.pointerEvents = "auto";
    label.style.cursor = "pointer";
    label.addEventListener("click", () => {
      if (!state.dragState.active) {
        selectNode(node.id);
      }
    });
    label.addEventListener("mouseenter", (event) => {
      emphasizeNode();
      handleTreeDragHover(node.id);
      showTreeTooltip(treeTooltip, node.title, event);
    });
    label.addEventListener("mousemove", (event) => moveTreeTooltip(treeTooltip, event));
    label.addEventListener("mouseleave", () => {
      normalizeNode();
      handleTreeDragHover(null);
      hideTreeTooltip(treeTooltip);
    });
    nodeGroup.appendChild(label);
    svg.appendChild(nodeGroup);
  });

  const canvas = document.createElement("div");
  canvas.className = "tree-graph-canvas";
  canvas.appendChild(svg);
  el.treeRoot.appendChild(canvas);
}

function getOrCreateTreeTooltip() {
  if (!el.treeRoot) {
    return null;
  }
  let tooltip = el.treeRoot.querySelector(".graph-tooltip");
  if (!tooltip) {
    tooltip = document.createElement("div");
    tooltip.className = "graph-tooltip hidden";
    el.treeRoot.appendChild(tooltip);
  }
  return tooltip;
}

function showTreeTooltip(tooltip, text, event) {
  if (!tooltip || !el.treeRoot) {
    return;
  }
  tooltip.textContent = text;
  tooltip.classList.remove("hidden");
  moveTreeTooltip(tooltip, event);
}

function moveTreeTooltip(tooltip, event) {
  if (!tooltip || !el.treeRoot) {
    return;
  }
  const rootRect = el.treeRoot.getBoundingClientRect();
  const x = (event.clientX - rootRect.left) + 12 + el.treeRoot.scrollLeft;
  const y = (event.clientY - rootRect.top) + 12 + el.treeRoot.scrollTop;
  tooltip.style.left = `${x}px`;
  tooltip.style.top = `${y}px`;
}

function hideTreeTooltip(tooltip) {
  if (!tooltip) {
    return;
  }
  tooltip.classList.add("hidden");
}

function renderTreeNode(node) {
  const wrapper = document.createElement("div");
  wrapper.className = buildTreeNodeClass(node.id);
  wrapper.dataset.nodeId = node.id;

  const button = document.createElement("button");
  button.className = "node-btn";
  button.title = node.title;
  if (node.id === state.selectedNodeId) {
    button.classList.add("active");
  }
  button.draggable = false;

  const time = new Date(node.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  button.innerHTML = `<span class="node-title">${escapeHtml(node.title)}</span><span class="node-meta">Depth ${node.depth} / ${time}</span>`;
  button.addEventListener("click", () => {
    if (state.suppressNodeClick) {
      state.suppressNodeClick = false;
      return;
    }
    selectNode(node.id);
  });
  attachListDragHandlers(button, node.id);
  wrapper.appendChild(button);

  if (node.children.length > 0) {
    const childrenWrap = document.createElement("div");
    childrenWrap.className = "node-children";
    node.children.forEach((child) => childrenWrap.appendChild(renderTreeNode(child)));
    wrapper.appendChild(childrenWrap);
  }

  return wrapper;
}

function buildTreeNodeClass(nodeId) {
  const classes = ["tree-node"];
  if (isNodeInDraggedSubtree(nodeId)) {
    classes.push("drag-subtree");
  }
  if (state.dragState.active && state.dragState.sourceNodeId === nodeId) {
    classes.push("drag-source");
  }
  if (state.dragState.active && state.dragState.targetNodeId === nodeId) {
    if (canMoveNodeUnderTarget(state.dragState.sourceNodeId, nodeId, getTreeSourceNodes())) {
      classes.push("drop-target");
    } else {
      classes.push("invalid-target");
    }
  }
  return classes.join(" ");
}

function buildGraphNodeGroupClass(nodeId) {
  const classes = ["tree-node-group"];
  if (isNodeInDraggedSubtree(nodeId)) {
    classes.push("drag-subtree");
  }
  if (state.dragState.active && state.dragState.sourceNodeId === nodeId) {
    classes.push("drag-source");
  }
  if (state.dragState.active && state.dragState.targetNodeId === nodeId) {
    if (canMoveNodeUnderTarget(state.dragState.sourceNodeId, nodeId, getTreeSourceNodes())) {
      classes.push("drop-target");
    } else {
      classes.push("invalid-target");
    }
  }
  return classes.join(" ");
}

function canDragTreeNode(nodeId) {
  if (state.treeBuildStatus === "processing") {
    return false;
  }
  const node = getNodeById(nodeId);
  return Boolean(node && node.parentId);
}

function collectSubtreeIds(nodeId, nodes = state.nodes) {
  const childrenByParent = new Map();
  nodes.forEach((node) => {
    if (!childrenByParent.has(node.parentId || null)) {
      childrenByParent.set(node.parentId || null, []);
    }
    childrenByParent.get(node.parentId || null).push(node.id);
  });

  const ids = new Set();
  const stack = [String(nodeId)];
  while (stack.length) {
    const current = stack.pop();
    if (ids.has(current)) {
      continue;
    }
    ids.add(current);
    const children = childrenByParent.get(current) || [];
    children.forEach((childId) => stack.push(childId));
  }
  return ids;
}

function normalizeSubtreeDepths(nodes, rootId) {
  const nodeMap = new Map(nodes.map((node) => [node.id, node]));
  const childrenByParent = new Map();
  nodes.forEach((node) => {
    if (!childrenByParent.has(node.parentId || null)) {
      childrenByParent.set(node.parentId || null, []);
    }
    childrenByParent.get(node.parentId || null).push(node.id);
  });

  const root = nodeMap.get(rootId);
  if (!root) {
    return;
  }

  const stack = [root.id];
  while (stack.length) {
    const currentId = stack.pop();
    const currentNode = nodeMap.get(currentId);
    const children = childrenByParent.get(currentId) || [];
    children.forEach((childId) => {
      const childNode = nodeMap.get(childId);
      if (!childNode) {
        return;
      }
      childNode.depth = (Number(currentNode.depth) || 0) + 1;
      stack.push(childId);
    });
  }
}

function canMoveNodeUnderTarget(sourceNodeId, targetNodeId, nodes = state.nodes) {
  if (!sourceNodeId || !targetNodeId || String(sourceNodeId) === String(targetNodeId)) {
    return false;
  }
  const sourceNode = nodes.find((node) => node.id === String(sourceNodeId));
  const targetNode = nodes.find((node) => node.id === String(targetNodeId));
  if (!sourceNode || !targetNode) {
    return false;
  }
  if (collectSubtreeIds(sourceNode.id, nodes).has(targetNode.id)) {
    return false;
  }
  return true;
}

function applyTreeMutationLocally(mutation) {
  if (!mutation?.type) {
    return false;
  }

  state.pendingTreeMutations = [...state.pendingTreeMutations, mutation];
  const nextNodes = applyPendingTreeMutationsTo(getTreeSourceNodes());
  state.nodes = nextNodes;
  state.treeNodes = nextNodes;

  if (mutation.type === "delete_subtree") {
    const selectedStillExists = state.selectedNodeId && nextNodes.some((node) => node.id === state.selectedNodeId);
    if (!selectedStillExists) {
      state.selectedNodeId = mutation.fallbackSelectedNodeId || null;
    }
  }

  render();
  return true;
}

function onDeleteSelectedNode() {
  const selected = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
  if (!selected) {
    return;
  }
  if (state.treeBuildStatus === "processing") {
    alert("트리 구성 중에는 노드를 편집할 수 없습니다.");
    return;
  }

  const subtreeIds = collectSubtreeIds(selected.id, getTreeSourceNodes());
  const ok = confirm(`선택한 노드와 하위 ${Math.max(0, subtreeIds.size - 1)}개 노드를 함께 삭제하시겠습니까?`);
  if (!ok) {
    return;
  }

  const fallbackSelectedNodeId = selected.parentId || null;
  applyTreeMutationLocally({
    type: "delete_subtree",
    nodeId: selected.id,
    fallbackSelectedNodeId
  });
}

function onTreeNodeDragStart(event, nodeId) {
  if (!canDragTreeNode(nodeId)) {
    event.preventDefault();
    return;
  }
  state.dragState = {
    sourceNodeId: String(nodeId),
    targetNodeId: null,
    active: true,
    previewElement: null,
    previewFollowsPointer: true,
    grabOffsetX: 0,
    grabOffsetY: 0,
    pointerX: event.clientX || 0,
    pointerY: event.clientY || 0
  };
  document.body.classList.add("tree-dragging");
  const preview = ensureTreeDragPreview(nodeId);
  event.dataTransfer.effectAllowed = "move";
  event.dataTransfer.setData("text/plain", String(nodeId));
  if (preview) {
    const buttonRect = event.currentTarget?.getBoundingClientRect?.();
    state.dragState.grabOffsetX = buttonRect ? Math.max(0, event.clientX - buttonRect.left) : 24;
    state.dragState.grabOffsetY = buttonRect ? Math.max(0, event.clientY - buttonRect.top) : 20;
    event.dataTransfer.setDragImage(getTransparentDragImage(), 0, 0);
  }
  updateTreeDragPointer(event.clientX, event.clientY);
}

function onTreeNodeDrag(event) {
  updateTreeDragPointer(event.clientX, event.clientY);
}

function onDocumentTreeDragOver(event) {
  if (!state.dragState.active || state.treeViewMode !== "list") {
    return;
  }
  updateTreeDragPointer(event.clientX, event.clientY);
}

function onTreeNodeDragOver(event, nodeId) {
  if (!state.dragState.active) {
    return;
  }
  event.preventDefault();
  updateTreeDragPointer(event.clientX, event.clientY);
  if (state.dragState.targetNodeId === String(nodeId)) {
    return;
  }
  state.dragState.targetNodeId = String(nodeId);
  event.dataTransfer.dropEffect = canMoveNodeUnderTarget(state.dragState.sourceNodeId, nodeId, getTreeSourceNodes()) ? "move" : "none";
  renderTree();
}

function onTreeNodeDragLeave(nodeId) {
  if (state.dragState.targetNodeId === String(nodeId)) {
    state.dragState.targetNodeId = null;
    renderTree();
  }
}

function onTreeNodeDrop(event, targetNodeId) {
  event.preventDefault();
  updateTreeDragPointer(event.clientX, event.clientY);
  commitTreeMove(state.dragState.sourceNodeId, String(targetNodeId));
}

function onTreeNodeDragEnd() {
  clearTreeDragState();
  renderTree();
}

function handleTreeDragHover(nodeId) {
  if (!state.dragState.active) {
    return;
  }
  const nextTargetId = nodeId ? String(nodeId) : null;
  if (state.dragState.targetNodeId === nextTargetId) {
    return;
  }
  state.dragState.targetNodeId = nextTargetId;
  renderTree();
}

function attachGraphDragHandlers(element, nodeId) {
  element.style.cursor = canDragTreeNode(nodeId) ? "grab" : "pointer";

  element.addEventListener("pointerdown", (event) => {
    if (!canDragTreeNode(nodeId) || event.button !== 0) {
      return;
    }
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    let dragging = false;

    const onMove = (moveEvent) => {
      if (dragging) {
        return;
      }
      const dx = Math.abs(moveEvent.clientX - startX);
      const dy = Math.abs(moveEvent.clientY - startY);
      if (dx < 6 && dy < 6) {
        return;
      }
      dragging = true;
      state.dragState = {
        sourceNodeId: String(nodeId),
        targetNodeId: null,
        active: true,
        previewElement: null,
        previewFollowsPointer: true,
        grabOffsetX: 0,
        grabOffsetY: 0,
        pointerX: moveEvent.clientX,
        pointerY: moveEvent.clientY
      };
      document.body.classList.add("tree-dragging");
      const preview = ensureTreeDragPreview(nodeId);
      if (preview) {
        state.dragState.grabOffsetX = Number(preview.dataset.anchorX) || (preview.getBoundingClientRect().width / 2);
        state.dragState.grabOffsetY = Number(preview.dataset.anchorY) || (preview.getBoundingClientRect().height / 2);
      }
      renderTree();
      updateTreeDragPointer(moveEvent.clientX, moveEvent.clientY);
    };

    const onDragMove = (moveEvent) => {
      if (!dragging) {
        onMove(moveEvent);
        return;
      }
      updateTreeDragPointer(moveEvent.clientX, moveEvent.clientY);
    };

    const onUp = () => {
      window.removeEventListener("pointermove", onDragMove);
      window.removeEventListener("pointerup", onUp);
      if (!dragging) {
        selectNode(nodeId);
        return;
      }
      const sourceNodeId = state.dragState.sourceNodeId;
      const targetNodeId = state.dragState.targetNodeId;
      clearTreeDragState();
      if (sourceNodeId && targetNodeId) {
        commitTreeMove(sourceNodeId, targetNodeId);
      } else {
        renderTree();
      }
    };

    window.addEventListener("pointermove", onDragMove);
    window.addEventListener("pointerup", onUp);
  });
}

function attachListDragHandlers(element, nodeId) {
  element.style.cursor = canDragTreeNode(nodeId) ? "grab" : "pointer";

  element.addEventListener("pointerdown", (event) => {
    if (!canDragTreeNode(nodeId) || event.button !== 0) {
      return;
    }
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    let dragging = false;

    const onMove = (moveEvent) => {
      const dx = Math.abs(moveEvent.clientX - startX);
      const dy = Math.abs(moveEvent.clientY - startY);
      if (!dragging) {
        if (dx < 6 && dy < 6) {
          return;
        }
        dragging = true;
        state.dragState = {
          sourceNodeId: String(nodeId),
          targetNodeId: null,
          active: true,
          previewElement: null,
          previewFollowsPointer: true,
          grabOffsetX: 0,
          grabOffsetY: 0,
          pointerX: moveEvent.clientX,
          pointerY: moveEvent.clientY
        };
        document.body.classList.add("tree-dragging");
        const preview = ensureTreeDragPreview(nodeId);
        const rect = element.getBoundingClientRect();
        state.dragState.grabOffsetX = Math.max(0, moveEvent.clientX - rect.left);
        state.dragState.grabOffsetY = Math.max(0, moveEvent.clientY - rect.top);
        renderTree();
        updateTreeDragPointer(moveEvent.clientX, moveEvent.clientY);
      } else {
        updateTreeDragPointer(moveEvent.clientX, moveEvent.clientY);
      }

      const hoveredElement = document.elementFromPoint(moveEvent.clientX, moveEvent.clientY);
      const nextTarget = hoveredElement?.closest?.(".tree-node[data-node-id]")?.dataset?.nodeId || null;
      if (state.dragState.targetNodeId !== nextTarget) {
        state.dragState.targetNodeId = nextTarget;
        renderTree();
      }
    };

    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      if (!dragging) {
        selectNode(nodeId);
        return;
      }
      const sourceNodeId = state.dragState.sourceNodeId;
      const targetNodeId = state.dragState.targetNodeId;
      state.suppressNodeClick = true;
      clearTreeDragState();
      if (sourceNodeId && targetNodeId) {
        commitTreeMove(sourceNodeId, targetNodeId);
      } else {
        renderTree();
      }
    };

    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });
}

function commitTreeMove(sourceNodeId, targetNodeId) {
  if (!sourceNodeId || !targetNodeId) {
    clearTreeDragState();
    renderTree();
    return;
  }
  if (state.treeBuildStatus === "processing") {
    alert("트리 구성 중에는 노드를 편집할 수 없습니다.");
    clearTreeDragState();
    renderTree();
    return;
  }
  if (!canMoveNodeUnderTarget(sourceNodeId, targetNodeId, getTreeSourceNodes())) {
    clearTreeDragState();
    renderTree();
    return;
  }

  applyTreeMutationLocally({
    type: "move_subtree",
    nodeId: String(sourceNodeId),
    newParentId: String(targetNodeId)
  });
  clearTreeDragState();
  renderTree();
}

function renderChat() {
  if (!el.chatFeed) {
    return;
  }

  el.chatFeed.innerHTML = "";

  const pathNodes = state.selectedNodeId ? getPathToNode(state.selectedNodeId) : [];
  let selectedBubble = null;
  pathNodes.forEach((node) => {
    if (isAutoSubtopicSeedNode(node)) {
      return;
    }
    const isSelected = node.id === state.selectedNodeId;
    const userBubble = makeBubble("user", node.userQuestion, node.timestamp, node.id, isSelected);
    const aiBubble = makeBubble("ai", node.aiAnswer, node.timestamp + 1000, node.id, isSelected);
    el.chatFeed.appendChild(userBubble);
    el.chatFeed.appendChild(aiBubble);
    if (isSelected) {
      selectedBubble = aiBubble;
    }
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
  if (selectedBubble) {
    requestAnimationFrame(() => {
      selectedBubble.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
    });
  } else {
    el.chatFeed.scrollTop = el.chatFeed.scrollHeight;
  }
}

function selectNode(nodeId) {
  if (nodeId == null) {
    return;
  }
  state.selectedNodeId = String(nodeId);
  render();
}

function isAutoSubtopicSeedNode(node) {
  const userQuestion = String(node?.userQuestion || "");
  const aiAnswer = String(node?.aiAnswer || "");

  if (userQuestion.startsWith("[AUTO_SUBTOPIC]")) {
    return true;
  }

  return userQuestion.startsWith("소주제:") && aiAnswer.includes("초기 소주제");
}

async function renderInsights() {
  const node = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;

  if (!node) {
    if (el.selectedNodeTitle) el.selectedNodeTitle.textContent = "선택 없음";
    if (el.selectedNodeMeta) el.selectedNodeMeta.textContent = "Depth - / Parent -";
    if (el.deleteSelectedNodeBtn) el.deleteSelectedNodeBtn.disabled = true;
    if (el.treeEditHint) el.treeEditHint.textContent = "노드를 다른 노드 위로 드래그하면 해당 노드의 자식으로 이동합니다.";
    if (el.depthBar) {
      el.depthBar.style.width = "10%";
      el.depthBar.style.background = "linear-gradient(90deg, #43dab8, #62b4d8)";
    }
    if (el.driftAlert) {
      el.driftAlert.textContent = "질문을 입력하면 학습 경로가 시작됩니다.";
      el.driftAlert.classList.remove("warn");
    }
    renderConversationSummaryPlaceholder("선택한 노드의 질문/답변 1쌍을 핵심 어구로 요약합니다.");
    state.insightRequestToken++;
    return;
  }

  const parentTitle = getParentTitleForNode(node);
  const canEditTree = state.treeBuildStatus !== "processing";

  el.selectedNodeTitle.textContent = node.title || "선택 노드";
  el.selectedNodeMeta.textContent = `Depth ${node.depth} / Parent: ${parentTitle}`;
  if (el.deleteSelectedNodeBtn) {
    el.deleteSelectedNodeBtn.disabled = !canEditTree;
  }
  if (el.treeEditHint) {
    if (!canEditTree) {
      el.treeEditHint.textContent = "트리 구성 중에는 편집할 수 없습니다.";
    } else if (!node.parentId) {
      el.treeEditHint.textContent = "루트 노드는 드래그 이동할 수 없지만 삭제는 가능합니다.";
    } else {
      el.treeEditHint.textContent = "노드를 다른 노드 위로 드래그하면 해당 노드의 자식으로 이동합니다.";
    }
  }

  applyInsightDepthUi(node.depth);

  const cacheKey = buildInsightCacheKey(node.id);
  const cachedInsight = state.insightCache.get(cacheKey);
  if (cachedInsight) {
    applyInsightPayload(cachedInsight, node);
    return;
  }

  renderConversationSummaryPlaceholder("대화 요약을 불러오는 중...");
  if (state.pendingInsightKeys.has(cacheKey)) {
    return;
  }

  const requestToken = ++state.insightRequestToken;
  state.pendingInsightKeys.add(cacheKey);

  try {
    const insight = await getNodeInsightApi(node.id, state.currentSession?.accessToken || "");
    state.insightCache.set(cacheKey, insight || {});

    if (requestToken !== state.insightRequestToken) {
      return;
    }

    const currentNode = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
    if (!currentNode || String(currentNode.id) !== String(node.id)) {
      return;
    }

    applyInsightPayload(insight || {}, currentNode);
  } catch (error) {
    if (requestToken !== state.insightRequestToken) {
      return;
    }
    renderConversationSummaryPlaceholder("대화 요약을 불러오지 못했습니다.");
  } finally {
    state.pendingInsightKeys.delete(cacheKey);
  }
}

function buildInsightCacheKey(nodeId) {
  return `${roomIdSafe(state.currentRoomId)}:${String(nodeId)}`;
}

function getParentTitleForNode(node) {
  const parentNode = node?.parentId ? getNodeById(node.parentId) : null;
  return parentNode ? parentNode.title : "없음 (최상위)";
}

function applyInsightPayload(insight, fallbackNode) {
  const depthFromApi = Number(insight?.depth);
  const depth = Number.isFinite(depthFromApi) ? depthFromApi : Number(fallbackNode?.depth || 0);
  const title = String(insight?.title || fallbackNode?.title || "선택 노드").trim();

  if (el.selectedNodeTitle) {
    el.selectedNodeTitle.textContent = title || "선택 노드";
  }
  if (el.selectedNodeMeta) {
    el.selectedNodeMeta.textContent = `Depth ${depth} / Parent: ${getParentTitleForNode(fallbackNode)}`;
  }

  applyInsightDepthUi(depth);
  renderConversationSummary(insight?.conversationSummary);
}

function applyInsightDepthUi(depthValue) {
  const depth = Number(depthValue) || 0;
  const ratio = Math.min(100, Math.round((depth / 7) * 100));

  if (el.depthBar) {
    el.depthBar.style.width = `${Math.max(10, ratio)}%`;
  }

  if (depth >= 5) {
    if (el.depthBar) {
      el.depthBar.style.background = "linear-gradient(90deg, #f7d16a, #ff8d7a)";
    }
    if (el.driftAlert) {
      el.driftAlert.textContent = "주의: 경로 깊이가 높습니다. 목표와의 정합성을 다시 확인하세요.";
      el.driftAlert.classList.add("warn");
    }
    return;
  }

  if (el.depthBar) {
    el.depthBar.style.background = "linear-gradient(90deg, #43dab8, #62b4d8)";
  }
  if (el.driftAlert) {
    el.driftAlert.textContent = "정상 범위: 학습 경로가 안정적으로 유지되고 있습니다.";
    el.driftAlert.classList.remove("warn");
  }
}

function renderConversationSummary(items) {
  if (!el.conversationSummaryList) {
    return;
  }

  const summaryItems = Array.isArray(items) ? items : [];
  if (summaryItems.length === 0) {
    renderConversationSummaryPlaceholder("선택 노드 요약을 생성할 수 없습니다.");
    return;
  }

  el.conversationSummaryList.innerHTML = "";

  summaryItems.forEach((item, index) => {
    const article = document.createElement("article");
    article.className = "summary-item";

    const head = document.createElement("div");
    head.className = "summary-item-head";

    const order = document.createElement("span");
    order.className = "summary-item-index";
    order.textContent = `${index + 1}.`;

    const keyword = document.createElement("strong");
    keyword.className = "summary-item-keyword";
    const keywordText = String(item?.keyword || "").trim();
    keyword.textContent = keywordText || `핵심 ${index + 1}`;

    head.appendChild(order);
    head.appendChild(keyword);
    article.appendChild(head);

    const details = Array.isArray(item?.details) ? item.details : [];
    if (details.length === 0) {
      const detail = document.createElement("p");
      detail.className = "summary-item-detail";
      detail.textContent = "핵심 내용 정리";
      article.appendChild(detail);
    } else {
      details.forEach((detailText) => {
        const detail = document.createElement("p");
        detail.className = "summary-item-detail";
        const normalizedDetail = String(detailText || "").replace(/\s+$/g, "");
        detail.textContent = normalizedDetail;
        if (normalizedDetail.trim()) {
          article.appendChild(detail);
        }
      });
    }

    el.conversationSummaryList.appendChild(article);
  });
}

function renderConversationSummaryPlaceholder(message) {
  if (!el.conversationSummaryList) {
    return;
  }
  const text = String(message || "").trim() || "요약이 준비되면 여기에 표시됩니다.";
  el.conversationSummaryList.innerHTML = `<p class="summary-placeholder">${escapeHtml(text)}</p>`;
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

function makeBubble(role, text, timestamp, nodeId = null, isSelected = false) {
  const bubble = document.createElement("div");
  bubble.className = `bubble ${role}`;
  if (isSelected) {
    bubble.classList.add("selected");
  }
  if (nodeId != null) {
    bubble.dataset.nodeId = String(nodeId);
  }
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

function changeGraphZoom(delta) {
  if (state.treeViewMode !== "graph") {
    return;
  }
  state.graphZoom = clamp(Number((state.graphZoom + delta).toFixed(2)), 0.6, 2.8);
  renderTree();
}

function resetGraphZoom() {
  state.graphZoom = 1;
  renderTree();
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
  const ordered = [...map.values()];
  ordered.forEach((node) => {
    node.children.sort(compareTreeNodeOrder);
  });
  ordered.sort(compareTreeNodeOrder);
  return ordered;
}

function compareTreeNodeOrder(left, right) {
  const leftTime = Number(left?.timestamp) || 0;
  const rightTime = Number(right?.timestamp) || 0;
  if (leftTime !== rightTime) {
    return leftTime - rightTime;
  }
  return String(left?.id || "").localeCompare(String(right?.id || ""));
}

function getTreeGraphLayout(nodes) {
  const tree = buildTree(nodes);
  const roots = tree.filter((node) => node.parentId === null);
  const xGap = 96;
  const yGap = 98;
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
          sourceId: parent.id,
          targetId: target.id,
          x1: parent.x,
          y1: parent.y + 18,
          x2: target.x,
          y2: target.y - 18
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
    height: margin * 2 + maxDepth * yGap + 36
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






