import { loginApi, signupApi } from "./api/auth-api.js";
import {
  askChatApi,
  checkRootTopicApi,
  createRecommendedChildNodeApi,
  createRoomApi,
  deleteRoomApi,
  getChildNodeRecommendationsApi,
  getNodeInsightApi,
  getRoomHistoryApi,
  getRoomTreeApi,
  getRoomsApi,
  updateRoomTitleApi,
  deleteNodeApi,  
  moveNodeApi,
  forceNodePlacementApi
} from "./api/chat-api.js";
import { deleteAccountApi, getProfileApi, updateProfileApi } from "./api/user-api.js";
import { CHAT_API_MODE } from "./config.js";
import { clearSession, loadSession, saveSession } from "./state/session-store.js";

if (typeof window !== "undefined") {
  window.__PATHLEARN_MAIN_READY = false;
}

let transparentDragImage = null;
let graphNodeClickTimer = null;
let focusCytoscape = null;
let lastCytoscapeTap = {
  nodeId: null,
  time: 0
};
let treeGraphResizeFrame = null;
let lastTreeGraphResizeKey = "";
let lastGraphNodePointerDown = {
  nodeId: null,
  time: 0,
  x: 0,
  y: 0
};

const UI_THEME_STORAGE_KEY = "pathlearn.uiTheme";
const ROOT_TOPIC_ACCEPTED_STORAGE_KEY = "pathlearn.rootTopicAcceptedNodeKeys";
const UI_THEME_IDS = new Set(["default", "mist", "sage", "lavender"]);
const GRAPH_ZOOM_PRESETS = [
  0.4, 1, 1.1, 1.2, 1.3, 1.4
];

const state = {
  currentSession: loadSession(),
  currentView: "landing",
  uiTheme: loadUiTheme(),
  treeViewMode: "list",
  graphZoom: 1,
  graphScopeMode: "range",
  focusGraphLayout: "mindmap",
  focusMindmapViewport: null,
  graphNodeShape: "circle",
  graphNodeSizeScale: 1,
  graphTreeScale: 1,
  graphResizeMode: false,
  treeFocusMode: false,
  treePanelCollapsed: false,
  insightPanelCollapsed: false,
  treePanelWidthBeforeCollapse: 340,
  insightPanelWidthBeforeCollapse: 340,
  graphNodeSizeById: new Map(),
  collapsedGraphNodeIds: new Set(),
  importantNodeIds: new Set(),
  roomDeleteMode: false,
  selectedRoomIdsForDelete: new Set(),
  localConversationRooms: [],
  suppressNodeClick: false,
  selectedNodeId: null,
  nodeSearchMode: "all",
  nodeSearchQuery: "",
  nodes: [],
  treeNodes: [],
  pendingTreeMutations: [],
  chatRooms: [],
  currentRoomId: null,
  isRoomDrawerOpen: false,
  treeBuildStatus: "completed",
  pendingTreeBuildJobs: 0,
  treeProcessingWatcherToken: 0,
  routeNotice: null,
  branchNotice: null,
  rootTopicNoticeNodeIds: new Set(),
  acceptedRootTopicNodeKeys: loadAcceptedRootTopicNodeKeys(),
  rootTopicPreflightResults: new Map(),
  pendingPlacementChecks: new Map(),
  insightCache: new Map(),
  pendingInsightKeys: new Set(),
  insightRequestToken: 0,
  childRecommendationCache: new Map(),
  pendingChildRecommendationKeys: new Set(),
  childRecommendationRequestToken: 0,
  isSendingMessage: false,
  chatSubmitStatusLabel: "",
  suppressRootTopicCheckOnce: false,
  forceCreateUnrelatedOnce: false,
  pendingRouteNoticePlan: null,
  rebuildModal: {
    open: false,
    mode: "rebuild",
    pathLabel: "",
    sourceRoomTitle: "",
    selectedNodeId: null,
    basePathNodeIds: [],
    extraOptions: [],
    selectedExtraBranchIds: new Set()
  },
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

  brandHomeBtn: document.getElementById("brandHomeBtn"),
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
  treeFocusModeBtn: document.getElementById("treeFocusModeBtn"),
  treeFocusCloseBtn: document.getElementById("treeFocusCloseBtn"),
  treeCollapseBtn: document.getElementById("treeCollapseBtn"),
  treeExpandBtn: document.getElementById("treeExpandBtn"),
  nodeCount: document.getElementById("nodeCount"),
  graphZoomInBtn: document.getElementById("graphZoomInBtn"),
  graphZoomOutBtn: document.getElementById("graphZoomOutBtn"),
  graphZoomResetBtn: document.getElementById("graphZoomResetBtn"),
  graphZoomLevel: document.getElementById("graphZoomLevel"),
  graphZoomSelect: document.getElementById("graphZoomSelect"),
  graphZoomFooter: document.getElementById("graphZoomFooter"),
  treeBuildStatus: document.getElementById("treeBuildStatus"),
  graphCircleShapeBtn: document.getElementById("graphCircleShapeBtn"),
  graphBoxShapeBtn: document.getElementById("graphBoxShapeBtn"),
  focusMindmapBtn: document.getElementById("focusMindmapBtn"),
  focusTreeBtn: document.getElementById("focusTreeBtn"),
  graphTreeScaleInBtn: document.getElementById("graphTreeScaleInBtn"),
  graphTreeScaleOutBtn: document.getElementById("graphTreeScaleOutBtn"),
  graphTreeScaleLevel: document.getElementById("graphTreeScaleLevel"),
  graphResizeModeBtn: document.getElementById("graphResizeModeBtn"),

  branchTag: document.getElementById("branchTag"),
  chatFeed: document.getElementById("chatFeed"),
  chatForm: document.getElementById("chatForm"),
  chatInput: document.getElementById("chatInput"),

  selectedNodeTitle: document.getElementById("selectedNodeTitle"),
  selectedNodeMeta: document.getElementById("selectedNodeMeta"),
  rebuildConversationBtn: document.getElementById("rebuildConversationBtn"),
  extractKnowledgeBtn: document.getElementById("extractKnowledgeBtn"),
  markImportantNodeBtn: document.getElementById("markImportantNodeBtn"),
  deleteSelectedNodeBtn: document.getElementById("deleteSelectedNodeBtn"),
  treeEditHint: document.getElementById("treeEditHint"),
  depthBar: document.getElementById("depthBar"),
  driftAlert: document.getElementById("driftAlert"),
  branchAlert: document.getElementById("branchAlert"),
  conversationSummaryList: document.getElementById("conversationSummaryList"),
  childNodeRecommendationList: document.getElementById("childNodeRecommendationList"),
  insightCollapseBtn: document.getElementById("insightCollapseBtn"),
  insightExpandBtn: document.getElementById("insightExpandBtn"),
  rebuildModalBackdrop: document.getElementById("rebuildModalBackdrop"),
  rebuildModalCloseBtn: document.getElementById("rebuildModalCloseBtn"),
  rebuildModalCancelBtn: document.getElementById("rebuildModalCancelBtn"),
  rebuildModalConfirmBtn: document.getElementById("rebuildModalConfirmBtn"),
  rebuildPathPreview: document.getElementById("rebuildPathPreview"),
  rebuildExtraOptions: document.getElementById("rebuildExtraOptions"),

  treeResizeHandle: document.getElementById("treeResizeHandle"),
  insightResizeHandle: document.getElementById("insightResizeHandle"),
  treePanel: document.querySelector(".tree-panel"),
  insightPanel: document.querySelector(".insight-panel"),
  nodeSearchContainers: document.querySelectorAll("[data-node-search]"),
  nodeSearchSelects: document.querySelectorAll(".node-search-select"),
  nodeSearchInputs: document.querySelectorAll(".node-search-input"),
  nodeSearchResults: document.querySelectorAll(".node-search-results"),
  themeOptionBtns: document.querySelectorAll("[data-theme-option]")
};

// 💡 정확히 여기에 쏙 들어오면 됩니다!
if (typeof marked !== 'undefined' && typeof hljs !== 'undefined') {
  marked.setOptions({
    highlight: function (code, lang) {
      const language = hljs.getLanguage(lang) ? lang : 'plaintext';
      return hljs.highlight(code, { language }).value;
    },
    langPrefix: 'hljs language-'
  });
}

applyUiTheme(state.uiTheme);
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
  
  // 🌟 기존 openHomeBtn을 지우고, brandHomeBtn(로고) 클릭 이벤트로 교체했습니다.
  const brandHomeBtn = document.getElementById("brandHomeBtn");
  brandHomeBtn?.addEventListener("click", () => {
    switchView("landing");
    render();
  });

  el.openSettingsBtn?.addEventListener("click", openSettingsView);

  // ──────────────────────────────────────────────────────────────────────────
  // 🔥 [최종 완성] 홈 화면 버튼 & 대화방 내부 버튼 통합 위임 + 디버깅 로그 주입
  // ──────────────────────────────────────────────────────────────────────────
  document.addEventListener("click", async (event) => {
    // 💡 홈 화면 버튼(#openRoomsBtn)과 대화방 내부 버튼(#roomDrawerToggle)을 모두 감시망에 넣습니다.
    const openRoomsBtn = event.target.closest("#openRoomsBtn") || 
                         event.target.closest(".open-rooms-btn") ||
                         event.target.closest("#roomDrawerToggle") || 
                         event.target.closest(".room-drawer-toggle");
    
    if (!openRoomsBtn) return; // 대화목록 관련 버튼이 아니면 무시
    
    event.preventDefault();
    
    // 📝 크롬 콘솔(F12)에 클릭이 되었는지, 어떤 버튼이 잡혔는지 로그를 찍어줍니다.
    console.log("🎯 [PathLearn 디버깅] 대화목록 버튼 클릭 감지됨! 엘리먼트 구조:", openRoomsBtn);
    
    if (!state.currentSession?.accessToken) {
      console.warn("🔒 로그인 세션이 없어 로그인 화면으로 이동합니다.");
      switchView("auth", "login");
      return;
    }
    
    // 현재 랜딩 페이지 등 다른 화면에 있다면 앱 화면으로 전환 후 강제 열기
    if (state.currentView !== "app") {
      console.log("🏠 홈 화면에서 대화방 뷰로 진입하며 드로어를 엽니다.");
      await openAppView();
      toggleRoomDrawer(true); 
    } else {
      // 이미 앱 화면에 있다면 누를 때마다 열림/닫힘 토글!
      console.log("💬 대화방 내부에서 드로어 토글(열림/닫힘)을 실행합니다.");
      toggleRoomDrawer(); 
    }
  });
  // ──────────────────────────────────────────────────────────────────────────
  // ──────────────────────────────────────────────────────────────────────────

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
  el.themeOptionBtns?.forEach((button) => {
    button.addEventListener("click", () => setUiTheme(button.dataset.themeOption || "default"));
  });
  el.nodeSearchSelects?.forEach((select) => {
    select.addEventListener("change", () => {
      setNodeSearchMode(select.value || "all");
    });
  });
  el.nodeSearchInputs?.forEach((input) => {
    input.addEventListener("input", () => {
      setNodeSearchQuery(input.value);
    });
  });

  el.treeListModeBtn?.addEventListener("click", () => setTreeViewMode("list"));
  el.treeGraphModeBtn?.addEventListener("click", () => setTreeViewMode("graph"));
  el.treeFocusModeBtn?.addEventListener("click", toggleTreeFocusMode);
  el.treeFocusCloseBtn?.addEventListener("click", () => setTreeFocusMode(false));
  el.treeCollapseBtn?.addEventListener("click", () => setPanelCollapsed("tree", true));
  el.treeExpandBtn?.addEventListener("click", () => setPanelCollapsed("tree", false));
  el.graphZoomInBtn?.addEventListener("click", () => changeGraphZoom(1));
  el.graphZoomOutBtn?.addEventListener("click", () => changeGraphZoom(-1));
  el.graphZoomSelect?.addEventListener("change", () => setGraphZoom(el.graphZoomSelect.value));
  el.graphZoomResetBtn?.addEventListener("click", resetGraphZoom);
  el.graphCircleShapeBtn?.addEventListener("click", () => setGraphNodeShape("circle"));
  el.graphBoxShapeBtn?.addEventListener("click", () => setGraphNodeShape("box"));
  el.focusMindmapBtn?.addEventListener("click", () => setFocusGraphLayout("mindmap"));
  el.focusTreeBtn?.addEventListener("click", () => setFocusGraphLayout("tree"));
  
  // ❌ [교정] 기존 트리 크기 조절 버튼 클릭 리스너(In/Out) 2줄은 마우스 휠 줌 대체로 인해 제거되었습니다.
  el.graphResizeModeBtn?.addEventListener("click", toggleGraphResizeMode);

  el.roomDrawerToggle?.addEventListener("click", () => toggleRoomDrawer());
  el.roomDrawerCloseBtn?.addEventListener("click", () => toggleRoomDrawer(false));
  el.roomDrawerBackdrop?.addEventListener("click", () => toggleRoomDrawer(false));
  el.roomCreateForm?.addEventListener("submit", onCreateRoom);
  el.roomDeleteModeBtn?.addEventListener("click", enterRoomDeleteMode);
  el.roomDeleteApplyBtn?.addEventListener("click", onApplyDeleteSelectedRooms);
  el.roomDeleteCancelBtn?.addEventListener("click", exitRoomDeleteMode);
  el.rebuildConversationBtn?.addEventListener("click", () => onOpenPathModal("rebuild"));
  el.extractKnowledgeBtn?.addEventListener("click", () => onOpenPathModal("extract"));
  el.insightCollapseBtn?.addEventListener("click", () => setPanelCollapsed("insight", true));
  el.insightExpandBtn?.addEventListener("click", () => setPanelCollapsed("insight", false));
  el.markImportantNodeBtn?.addEventListener("click", toggleSelectedNodeImportant);
  el.deleteSelectedNodeBtn?.addEventListener("click", onDeleteSelectedNode);
  el.rebuildModalCloseBtn?.addEventListener("click", closeRebuildModal);
  el.rebuildModalCancelBtn?.addEventListener("click", closeRebuildModal);
  el.rebuildModalConfirmBtn?.addEventListener("click", confirmRebuildConversation);
  el.rebuildModalBackdrop?.addEventListener("click", (event) => {
    if (event.target === el.rebuildModalBackdrop) {
      closeRebuildModal();
    }
  });
  document.addEventListener("dragover", onDocumentTreeDragOver);
  setupTreeGraphResizeObserver();


  // ==========================================================================
  // 🎛️ [올인원 통합] 지식 트리 그래프 캔버스 휠 줌 & 드래그 앤 팬 인터랙션 코어
  // ==========================================================================
  const treeRootContainer = document.getElementById("treeRoot");
  
  if (treeRootContainer) {
    // ------------------------------------------------------------------------
    // 🔄 1. 마우스 휠 스크롤 축소/확대(Zoom In/Out) 엔진 (여기가 유실되었었습니다!)
    // ------------------------------------------------------------------------
    treeRootContainer.addEventListener("wheel", (e) => {
      if (state.treeViewMode !== "graph") return;
      e.preventDefault(); // 브라우저 메인 윈도우 스크롤 다운 버그 차단
      
      const scaleIntensity = 0.05; // 줌 감도 민감도 세팅
      if (e.deltaY < 0) {
        changeGraphTreeScale(scaleIntensity);  // 휠 업: 확대
      } else {
        changeGraphTreeScale(-scaleIntensity); // 휠 다운: 축소
      }
    }, { passive: false });

    // ------------------------------------------------------------------------
    // 🖐️ 2. 빈 공간 드래그 스크롤(Drag & Pan) 엔진 (엘리먼트 유실 방지 최종본)
    // ------------------------------------------------------------------------
    let isDown = false;
    let startX, startY;
    let scrollLeft, scrollTop;

    // 마우스 꾹 누를 때
    treeRootContainer.addEventListener("pointerdown", (e) => {
      if (state.treeViewMode !== "graph" || state.dragState.active) return;
      if (e.target.closest(".tree-node-group") || e.target.closest("button")) return;

      isDown = true;
      treeRootContainer.classList.add("grabbing"); 
      
      // 🎯 매번 증발하는 자식 대신, 영구적으로 살아있는 treeRootContainer 기준 좌표와 스크롤을 맵핑합니다.
      startX = e.pageX - treeRootContainer.offsetLeft;
      startY = e.pageY - treeRootContainer.offsetTop;
      scrollLeft = treeRootContainer.scrollLeft;
      scrollTop = treeRootContainer.scrollTop;
      
      treeRootContainer.setPointerCapture(e.pointerId);
    });

    // 누른 채 밀 때
    treeRootContainer.addEventListener("pointermove", (e) => {
      if (!isDown) return;
      e.preventDefault();
      
      const x = e.pageX - treeRootContainer.offsetLeft;
      const y = e.pageY - treeRootContainer.offsetTop;
      
      // ⚡ 피그마 특유의 쫀득한 가속도를 위해 1.5배 보정치를 주었습니다.
      const walkX = (x - startX) * 1.5; 
      const walkY = (y - startY) * 1.5;
      
      // 🎯 부모 컨테이너의 스크롤을 직접 다이렉트로 밀어버립니다.
      treeRootContainer.scrollLeft = scrollLeft - walkX;
      treeRootContainer.scrollTop = scrollTop - walkY;
    });

    // 드래그 뗄 때 리셋
    const stopDragging = (e) => {
      if (!isDown) return;
      isDown = false;
      treeRootContainer.classList.remove("grabbing");
      if (treeRootContainer.hasPointerCapture(e.pointerId)) {
        treeRootContainer.releasePointerCapture(e.pointerId);
      }
    };

    treeRootContainer.addEventListener("pointerup", stopDragging);
    treeRootContainer.addEventListener("pointerleave", stopDragging);
  }
}

function setupTreeGraphResizeObserver() {
  const targets = [el.treeRoot, el.treePanel].filter(Boolean);
  const scheduleResizeRender = () => {
    const rect = el.treeRoot?.getBoundingClientRect();
    if (!rect) {
      return;
    }
    const sizeKey = `${Math.round(rect.width)}x${Math.round(rect.height)}`;
    if (sizeKey === lastTreeGraphResizeKey) {
      return;
    }
    lastTreeGraphResizeKey = sizeKey;
    if (treeGraphResizeFrame) {
      cancelAnimationFrame(treeGraphResizeFrame);
    }
    treeGraphResizeFrame = requestAnimationFrame(() => {
      treeGraphResizeFrame = null;
      if (state.currentView === "app" && state.treeViewMode === "graph") {
        renderTree();
      }
    });
  };

  if (typeof ResizeObserver === "function") {
    const observer = new ResizeObserver(scheduleResizeRender);
    targets.forEach((target) => observer.observe(target));
  }

  window.addEventListener("resize", scheduleResizeRender);
}

function render() {
  switchView(state.currentView);
  renderRoomDrawer();
  renderNodeSearch();
  renderTree();
  renderChat();
  renderInsights();
  renderRebuildModal();
  renderThemeControls();
  syncChatInputAvailability();
}

function cloneNode(node) {
  return { ...node };
}

function loadUiTheme() {
  try {
    const saved = localStorage.getItem(UI_THEME_STORAGE_KEY);
    return UI_THEME_IDS.has(saved) ? saved : "default";
  } catch {
    return "default";
  }
}

function setUiTheme(themeId) {
  state.uiTheme = UI_THEME_IDS.has(themeId) ? themeId : "default";
  applyUiTheme(state.uiTheme);
  try {
    localStorage.setItem(UI_THEME_STORAGE_KEY, state.uiTheme);
  } catch {
    // 로컬 저장이 막힌 환경에서는 현재 화면에만 적용합니다.
  }
  renderThemeControls();
}

function applyUiTheme(themeId) {
  if (!document?.body) {
    return;
  }
  document.body.dataset.theme = UI_THEME_IDS.has(themeId) ? themeId : "default";
}

function renderThemeControls() {
  el.themeOptionBtns?.forEach((button) => {
    button.classList.toggle("active", button.dataset.themeOption === state.uiTheme);
  });
}

function isLocalConversationRoomId(roomId) {
  return typeof roomId === "string" && roomId.startsWith("local-room:");
}

function getLocalConversationRoom(roomId = state.currentRoomId) {
  return state.localConversationRooms.find((room) => room.id === roomId) || null;
}

function getVisibleConversationRooms() {
  return [...state.localConversationRooms, ...state.chatRooms];
}

function isCurrentRoomLocal() {
  return isLocalConversationRoomId(state.currentRoomId);
}

function persistCurrentLocalConversationSelection() {
  if (!isCurrentRoomLocal()) {
    return;
  }
  state.localConversationRooms = state.localConversationRooms.map((room) => (
    room.id === state.currentRoomId
      ? { ...room, selectedNodeId: state.selectedNodeId }
      : room
  ));
}

function persistCurrentLocalConversationState() {
  if (!isCurrentRoomLocal()) {
    return;
  }
  state.localConversationRooms = state.localConversationRooms.map((room) => (
    room.id === state.currentRoomId
      ? {
          ...room,
          nodes: state.nodes.map(cloneNode),
          treeNodes: state.treeNodes.map(cloneNode),
          selectedNodeId: state.selectedNodeId
        }
      : room
  ));
}

function getTreeSourceNodes() {
  return state.treeNodes.length ? state.treeNodes : state.nodes;
}

function getRenderableTreeNodes() {
  if (state.treeBuildStatus === "processing") {
    const selectedExistsInTree = state.selectedNodeId
      && state.treeNodes.some((node) => String(node.id) === String(state.selectedNodeId));
    const selectedExistsInNodes = state.selectedNodeId
      && state.nodes.some((node) => String(node.id) === String(state.selectedNodeId));
    if (selectedExistsInNodes && !selectedExistsInTree) {
      return state.nodes;
    }
    return state.treeNodes;
  }
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

  const sourceGroup = [...sourceSvg.querySelectorAll(".tree-node-group[data-node-id]")]
    .find((group) => group.dataset.nodeId === String(nodeId));
  if (!sourceGroup || !subtreeIds.size) {
    return null;
  }
  const nodeGroups = [sourceGroup];

  const bounds = nodeGroups.reduce((acc, group) => {
    const shape = group.querySelector(".tree-node-circle, .tree-node-box");
    const label = group.querySelector("text");
    const isBox = shape?.tagName?.toLowerCase() === "rect";
    const rx = Number(shape?.getAttribute("rx")) || Number(shape?.getAttribute("r")) || 20;
    const ry = Number(shape?.getAttribute("ry")) || Number(shape?.getAttribute("r")) || 20;
    const shapeMinX = isBox
      ? Number(shape?.getAttribute("x")) || 0
      : (Number(shape?.getAttribute("cx")) || 0) - rx;
    const shapeMaxX = isBox
      ? shapeMinX + (Number(shape?.getAttribute("width")) || 0)
      : (Number(shape?.getAttribute("cx")) || 0) + rx;
    const shapeMinY = isBox
      ? Number(shape?.getAttribute("y")) || 0
      : (Number(shape?.getAttribute("cy")) || 0) - ry;
    const shapeMaxY = isBox
      ? shapeMinY + (Number(shape?.getAttribute("height")) || 0)
      : (Number(shape?.getAttribute("cy")) || 0) + ry;
    const textWidth = Math.max(44, String(label?.textContent || "").length * 8);
    const centerX = (shapeMinX + shapeMaxX) / 2;
    acc.minX = Math.min(acc.minX, shapeMinX, centerX - textWidth / 2);
    acc.maxX = Math.max(acc.maxX, shapeMaxX, centerX + textWidth / 2);
    acc.minY = Math.min(acc.minY, shapeMinY - 6);
    acc.maxY = Math.max(acc.maxY, shapeMaxY + 12);
    return acc;
  }, { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity });

  const sourceShape = sourceGroup?.querySelector(".tree-node-circle, .tree-node-box");

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

  nodeGroups.forEach((group) => {
    const clone = group.cloneNode(true);
    clone.setAttribute("class", "tree-node-group");
    clone.querySelectorAll(".tree-node-hidden-badge").forEach((badge) => badge.remove());
    clone.querySelectorAll(".tree-node-depth-shadow").forEach((shadow) => shadow.remove());
    clone.querySelectorAll(".tree-node-important-marker").forEach((marker) => marker.remove());
    clone.querySelectorAll(".tree-node-circle").forEach((shape) => {
      shape.setAttribute("cx", String((Number(shape.getAttribute("cx")) || 0) - minX));
      shape.setAttribute("cy", String((Number(shape.getAttribute("cy")) || 0) - minY));
      shape.setAttribute("class", "tree-drag-preview-node-circle");
    });
    clone.querySelectorAll(".tree-node-box").forEach((rect) => {
      rect.setAttribute("x", String((Number(rect.getAttribute("x")) || 0) - minX));
      rect.setAttribute("y", String((Number(rect.getAttribute("y")) || 0) - minY));
      rect.setAttribute("class", "tree-drag-preview-node-circle");
    });
    clone.querySelectorAll("text").forEach((label) => {
      label.setAttribute("x", String((Number(label.getAttribute("x")) || 0) - minX));
      label.setAttribute("y", String((Number(label.getAttribute("y")) || 0) - minY));
      label.querySelectorAll("tspan").forEach((tspan) => {
        tspan.setAttribute("x", String((Number(tspan.getAttribute("x")) || 0) - minX));
      });
      label.setAttribute("class", "tree-drag-preview-node-label");
      label.style.pointerEvents = "none";
    });
    svg.appendChild(clone);
  });

  preview.appendChild(svg);
  document.body.appendChild(preview);
  if (sourceShape) {
    const isBox = sourceShape.tagName.toLowerCase() === "rect";
    const anchorX = isBox
      ? (Number(sourceShape.getAttribute("x")) || 0) + (Number(sourceShape.getAttribute("width")) || 0) / 2
      : Number(sourceShape.getAttribute("cx")) || 0;
    const anchorY = isBox
      ? (Number(sourceShape.getAttribute("y")) || 0) + (Number(sourceShape.getAttribute("height")) || 0) / 2
      : Number(sourceShape.getAttribute("cy")) || 0;
    preview.dataset.anchorX = String(anchorX - minX);
    preview.dataset.anchorY = String(anchorY - minY);
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
  clearRouteNotice();
  clearBranchNotice();
  state.pendingPlacementChecks.clear();
  clearTreeDragState();
}

function clearInsightRelatedCaches() {
  state.insightCache.clear();
  state.pendingInsightKeys.clear();
  state.insightRequestToken++;
  state.childRecommendationCache.clear();
  state.pendingChildRecommendationKeys.clear();
  state.childRecommendationRequestToken++;
}

function switchView(view, authTab) {
  state.currentView = view;
  document.body.classList.toggle("app-active", view === "app");
  document.body.classList.toggle("tree-focus-active", view === "app" && state.treeFocusMode);
  applyPanelCollapseState();

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

function setPanelCollapsed(panel, collapsed) {
  if (panel === "tree") {
    if (collapsed) {
      state.treePanelWidthBeforeCollapse = getPanelCssWidth("--tree-panel-width", state.treePanelWidthBeforeCollapse);
    } else {
      restorePanelWidth("--tree-panel-width", state.treePanelWidthBeforeCollapse);
    }
    state.treePanelCollapsed = Boolean(collapsed);
    if (state.treePanelCollapsed && state.treeFocusMode) {
      setTreeFocusMode(false);
    }
  } else if (panel === "insight") {
    if (collapsed) {
      state.insightPanelWidthBeforeCollapse = getPanelCssWidth("--insight-panel-width", state.insightPanelWidthBeforeCollapse);
    } else {
      restorePanelWidth("--insight-panel-width", state.insightPanelWidthBeforeCollapse);
    }
    state.insightPanelCollapsed = Boolean(collapsed);
  } else {
    return;
  }

  applyPanelCollapseState();
  queueTreeGraphResize();
}

function applyPanelCollapseState() {
  const isAppView = state.currentView === "app";
  document.body.classList.toggle("tree-panel-collapsed", isAppView && state.treePanelCollapsed);
  document.body.classList.toggle("insight-panel-collapsed", isAppView && state.insightPanelCollapsed);
  el.treeCollapseBtn?.classList.toggle("hidden", !isAppView || state.treePanelCollapsed);
  el.treeExpandBtn?.classList.toggle("hidden", !isAppView || !state.treePanelCollapsed);
  el.insightCollapseBtn?.classList.toggle("hidden", !isAppView || state.insightPanelCollapsed);
  el.insightExpandBtn?.classList.toggle("hidden", !isAppView || !state.insightPanelCollapsed);
}

function getPanelCssWidth(cssVar, fallback) {
  const value = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue(cssVar));
  return Number.isFinite(value) && value > 80 ? value : fallback;
}

function restorePanelWidth(cssVar, width) {
  const nextWidth = clamp(Number(width) || 340, 260, getPanelResizeMaxWidth());
  document.documentElement.style.setProperty(cssVar, `${nextWidth}px`);
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
  clearPendingGraphNodeClick();
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

    if (state.chatRooms.length === 0 && state.localConversationRooms.length === 0) {
      state.currentRoomId = null;
      state.nodes = [];
      state.treeNodes = [];
      state.selectedNodeId = null;
      state.collapsedGraphNodeIds.clear();
      state.graphNodeSizeById.clear();
      state.importantNodeIds.clear();
      state.treeBuildStatus = "completed";
      state.treeProcessingWatcherToken++;
      return;
    }

    if (isCurrentRoomLocal() && getLocalConversationRoom()) {
      loadLocalConversationRoom(state.currentRoomId);
      return;
    }

    if (state.chatRooms.length === 0 && state.localConversationRooms.length > 0) {
      clearPendingTreeMutations();
      state.currentRoomId = state.localConversationRooms[0].id;
      loadLocalConversationRoom(state.currentRoomId);
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
      clearInsightRelatedCaches();
    state.selectedNodeId = null;
    state.collapsedGraphNodeIds.clear();
    state.graphNodeSizeById.clear();
    state.importantNodeIds.clear();
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
  // 💡 [핵심 교정] 대화방 진입 시 재렌더링되는 진짜 최신 HTML 엘리먼트들을 실시간으로 새로 낚아챕니다.
  const currentDrawer = document.getElementById("roomDrawer") || el.roomDrawer;
  const currentBackdrop = document.getElementById("roomDrawerBackdrop") || el.roomDrawerBackdrop;
  const currentAppView = document.getElementById("appView") || el.appView;
  const currentDeleteModeBtn = document.getElementById("roomDeleteModeBtn") || el.roomDeleteModeBtn;
  const currentDeleteApplyBtn = document.getElementById("roomDeleteApplyBtn") || el.roomDeleteApplyBtn;
  const currentDeleteCancelBtn = document.getElementById("roomDeleteCancelBtn") || el.roomDeleteCancelBtn;
  const currentRoomList = document.getElementById("roomList") || el.roomList;

  if (!currentDrawer) {
    return;
  }

  // 유령 노드가 아닌 화면에 살아있는 진짜 드로어에 클래스를 끄고 켭니다.
  currentDrawer.classList.toggle("open", state.isRoomDrawerOpen);
  currentBackdrop?.classList.toggle("open", state.isRoomDrawerOpen);
  currentAppView?.classList.toggle("drawer-open", state.isRoomDrawerOpen);

  currentDeleteModeBtn?.classList.toggle("hidden", state.roomDeleteMode);
  currentDeleteApplyBtn?.classList.toggle("hidden", !state.roomDeleteMode);
  currentDeleteCancelBtn?.classList.toggle("hidden", !state.roomDeleteMode);

  if (!currentRoomList) {
    return;
  }

  currentRoomList.innerHTML = "";
  getVisibleConversationRooms().forEach((room) => {
    const row = document.createElement("div");
    row.className = "room-item-row";

    if (state.roomDeleteMode && !room.localOnly) {
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
    btn.className = `room-item ${room.localOnly ? "local-room" : ""} ${room.id === state.currentRoomId ? "active" : ""}`.trim();
    btn.innerHTML = `<span class="title">${escapeHtml(room.title || "새 대화")}</span><span class="meta">${room.localOnly ? escapeHtml(room.metaLabel || "재구성 대화") : `#${room.id}`}</span>`;
    btn.addEventListener("click", async () => {
      if (state.roomDeleteMode) {
        return;
      }
      clearPendingTreeMutations();
      state.treeProcessingWatcherToken++;
      state.currentRoomId = room.id;
      if (room.localOnly) {
        loadLocalConversationRoom(room.id);
      } else {
        await loadRoomHistory(room.id);
      }
      toggleRoomDrawer(false);
      render();
    });

    row.appendChild(btn);
    currentRoomList.appendChild(row); // 💡 실시간으로 잡은 리스트 영역에 방들을 렌더링합니다.
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
    state.currentRoomId = state.chatRooms.length
      ? state.chatRooms[0].id
      : (state.localConversationRooms.length ? state.localConversationRooms[0].id : null);
    if (state.currentRoomId) {
      if (isCurrentRoomLocal()) {
        loadLocalConversationRoom(state.currentRoomId);
      } else {
        await loadRoomHistory(state.currentRoomId);
      }
    } else {
      state.nodes = [];
      state.treeNodes = [];
      clearPendingTreeMutations();
      state.selectedNodeId = null;
      state.collapsedGraphNodeIds.clear();
      state.graphNodeSizeById.clear();
      state.importantNodeIds.clear();
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
  state.collapsedGraphNodeIds.clear();
  state.graphNodeSizeById.clear();
  state.importantNodeIds.clear();
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
        detectPlacementChangeNotice(targetNodeId);
      }
      render();
      return true;
    }
  }

  const applied = await loadRoomHistoryWithOptions(roomId, { keepTreeWhileProcessing: false });
  if (applied && targetNodeId && state.nodes.some((node) => node.id === targetNodeId)) {
    state.selectedNodeId = targetNodeId;
    detectPlacementChangeNotice(targetNodeId);
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

    state.treeBuildStatus = "completed";
    state.treeNodes = state.nodes;
    if (targetNodeId && state.nodes.some((node) => node.id === targetNodeId)) {
      state.selectedNodeId = targetNodeId;
      detectPlacementChangeNotice(targetNodeId);
    }
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
  if (persistedId !== tempId) {
    transferGraphNodeUiState(tempId, persistedId);
  }
  expandCollapsedAncestorsForNode(persistedId, state.nodes);
  expandCollapsedAncestorsForNode(persistedId, state.treeNodes);
  transferRouteNoticeNodeId(tempId, persistedId);
  transferBranchNoticeNodeId(tempId, persistedId);
  transferRootTopicPreflightResult(tempId, persistedId);
  return persistedId;
}

async function onSendMessage(event) {
  event.preventDefault();
  if (state.isSendingMessage) {
    return;
  }

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
  const previousTreeBuildStatus = state.treeBuildStatus;
  const localRoom = getLocalConversationRoom();
  let effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;

  try {
    setChatSubmitBusy(true, "답변 생성 중...");
    if (!state.currentRoomId) {
      const roomId = await createRoomWithFallbackTitle();
      state.currentRoomId = roomId;
      effectiveRoomId = roomId;
      await refreshRoomsOnly();
    }

    const parent = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
    const nextDepth = parent ? parent.depth + 1 : 0;
    let parentId = parent ? parent.id : null;
    let forceCreateUnrelated = state.forceCreateUnrelatedOnce;
    state.forceCreateUnrelatedOnce = false;
    const activeParent = parentId ? getNodeById(parentId) : null;
    const effectiveNextDepth = activeParent ? Number(activeParent.depth || 0) + 1 : 0;
    let pendingNotice = forceCreateUnrelated ? state.pendingRouteNoticePlan : null;
    let localRootTopicMismatch = false;
    const rootTopicForCheck = getRootTopicLabelForParent(activeParent);
    state.pendingRouteNoticePlan = null;

    if (!forceCreateUnrelated && parentId) {
      const rootTopicDecision = await resolveRootTopicDecision({
        roomId: effectiveRoomId,
        parentId,
        question
      });
      if (rootTopicDecision === "new_room") {
        await startNewRoomWithQuestion(question);
        return;
      }
      if (rootTopicDecision === "cancel") {
        if (el.chatInput) {
          el.chatInput.value = question;
        }
        render();
        setChatSubmitBusy(false);
        return;
      }
      if (rootTopicDecision === "continue_unrelated") {
        forceCreateUnrelated = true;
        pendingNotice = state.pendingRouteNoticePlan;
        state.pendingRouteNoticePlan = null;
      }
      if (rootTopicDecision === "check_failed") {
        localRootTopicMismatch = shouldUseLocalRootTopicMismatchNotice(question, activeParent);
      }
    }

    tempId = `n_${Date.now().toString(36)}`;

    state.nodes.push({
      id: tempId,
      parentId,
      title: summarizeTitle(question),
      userQuestion: question,
      aiAnswer: "응답 생성 중...",
      depth: effectiveNextDepth,
      timestamp: Date.now()
    });
    if (pendingNotice?.kind === "branch") {
      clearRouteNotice();
      setBranchNotice({
        nodeId: tempId,
        message: pendingNotice.message,
        createdAt: pendingNotice.createdAt
      });
    } else if (pendingNotice) {
      setRouteNotice({ ...pendingNotice, nodeId: tempId });
      clearBranchNotice();
    } else if (localRootTopicMismatch) {
      setRouteNotice({
        nodeId: tempId,
        type: "strong",
        kind: "topic",
        message: `대주제 '${getRootTopicLabelForParent(activeParent)}'와 관계가 낮아 보입니다. 답변 후 진행 방식을 선택할 수 있습니다.`,
        createdAt: Date.now()
      });
      clearBranchNotice();
    } else {
      clearRouteNotice();
      clearBranchNotice();
    }
    if (!isCurrentRoomLocal()) {
      state.treeBuildStatus = "processing";
    }
    state.selectedNodeId = tempId;
    expandCollapsedAncestorsForNode(tempId, state.nodes);
    if (isCurrentRoomLocal()) {
      state.treeNodes = state.nodes.map(cloneNode);
      persistCurrentLocalConversationState();
    }
    render();
    if (localRootTopicMismatch) {
      const choice = await openRootTopicDecisionDialog({
        unrelated: true,
        rootTopic: getRootTopicLabelForParent(activeParent),
        similarity: 0
      });
      if (choice === "new_room") {
        state.nodes = state.nodes.filter((node) => node.id !== tempId);
        state.selectedNodeId = previousSelectedNodeId;
        state.treeBuildStatus = previousTreeBuildStatus;
        clearRouteNotice();
        clearBranchNotice();
        await startNewRoomWithQuestion(question);
        return;
      }
      if (choice === "cancel") {
        state.nodes = state.nodes.filter((node) => node.id !== tempId);
        state.selectedNodeId = previousSelectedNodeId;
        state.treeBuildStatus = previousTreeBuildStatus;
        clearRouteNotice();
        clearBranchNotice();
        if (el.chatInput) {
          el.chatInput.value = question;
        }
        render();
        setChatSubmitBusy(false);
        return;
      }
      forceCreateUnrelated = true;
    }
    beginRootTopicPreflight({
      roomId: effectiveRoomId,
      parentId,
      question,
      nodeId: tempId,
      skip: localRootTopicMismatch || forceCreateUnrelated || !parentId
    });

    const response = await askChatApi({
      roomId: effectiveRoomId,
      message: question,
      parentId,
      forceCreateUnrelated,
      skipRootTopicGuard: forceCreateUnrelated,
      rootTopic: rootTopicForCheck,
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
      nextDepth: effectiveNextDepth
    });
    maybeHandleAssistantOutOfScopeNotice(response, persistedNodeId, question, parentId);
    handleDeferredRootTopicNotice(response, persistedNodeId);
    if (localRootTopicMismatch && !forceCreateUnrelated && persistedNodeId && !state.rootTopicNoticeNodeIds.has(String(persistedNodeId))) {
      handleLocalRootTopicMismatchNotice(persistedNodeId, question, parentId);
    }
    rememberPlacementCheck(persistedNodeId, parentId, question);
    schedulePostAnswerRootTopicCheck({
      roomId: effectiveRoomId,
      parentId,
      question,
      nodeId: persistedNodeId,
      skip: forceCreateUnrelated || !parentId
    });
    if (isCurrentRoomLocal()) {
      state.treeNodes = state.nodes.map(cloneNode);
      if (persistedNodeId && state.nodes.some((node) => node.id === persistedNodeId)) {
        state.selectedNodeId = persistedNodeId;
      }
      persistCurrentLocalConversationState();
      render();
      setChatSubmitBusy(false);
      return;
    }

    const applied = await loadRoomHistoryWithOptions(state.currentRoomId, { keepTreeWhileProcessing: true });
    if (applied && persistedNodeId && state.nodes.some((node) => node.id === persistedNodeId)) {
      state.selectedNodeId = persistedNodeId;
      expandCollapsedAncestorsForNode(persistedNodeId, state.nodes);
      expandCollapsedAncestorsForNode(persistedNodeId, state.treeNodes);
      detectPlacementChangeNotice(persistedNodeId);
    }
    render();
    if (state.treeBuildStatus === "processing") {
      startTreeProcessingWatcher(state.currentRoomId, persistedNodeId);
    } else if (applied) {
      state.treeNodes = state.nodes;
      render();
    }
    setChatSubmitBusy(false);
  } catch (error) {
    if (tempId) {
      state.nodes = state.nodes.filter((node) => node.id !== tempId);
      state.selectedNodeId = previousSelectedNodeId;
    }
    state.treeBuildStatus = previousTreeBuildStatus;
    if (isCurrentRoomLocal()) {
      state.treeNodes = state.nodes.map(cloneNode);
      persistCurrentLocalConversationState();
    }
    const rootReject = parseRootTopicReject(error);
    if (rootReject) {
      render();
      const choice = await openRootTopicDecisionDialog(rootReject);
      if (choice === "continue") {
        state.suppressRootTopicCheckOnce = true;
        state.forceCreateUnrelatedOnce = true;
        state.pendingRouteNoticePlan = {
          type: "strong",
          kind: "topic",
          message: `대주제 '${rootReject.rootTopic || "현재 대화"}'와 관계가 낮은 노드를 생성했습니다.`,
          createdAt: Date.now()
        };
        if (el.chatInput) {
          el.chatInput.value = question;
        }
        setChatSubmitBusy(false);
        el.chatForm?.requestSubmit();
        return;
      }
      if (choice === "new_room") {
        clearRouteNotice();
        clearBranchNotice();
        await startNewRoomWithQuestion(question);
        return;
      }
      render();
      setChatSubmitBusy(false);
      return;
    }
    setAuthMessage(`전송 실패: ${toUiError(error)}`, "error");
    render();
    setChatSubmitBusy(false);
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
    clearInsightRelatedCaches();
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
  clearInsightRelatedCaches();
  state.selectedNodeId = null;
  state.treeBuildStatus = "completed";
  state.treeProcessingWatcherToken++;
  clearSession();
  switchView("landing");
  render();
}

function setNodeSearchMode(mode) {
  state.nodeSearchMode = ["all", "question", "answer"].includes(mode) ? mode : "all";
  renderNodeSearch();
}

function setNodeSearchQuery(query) {
  state.nodeSearchQuery = String(query || "");
  renderNodeSearch();
}

function renderNodeSearch() {
  const isSearchVisible = state.currentView === "app";

  el.nodeSearchContainers?.forEach((container) => {
    container.classList.toggle("hidden", !isSearchVisible);
  });

  el.nodeSearchSelects?.forEach((select) => {
    if (select.value !== state.nodeSearchMode) {
      select.value = state.nodeSearchMode;
    }
  });

  el.nodeSearchInputs?.forEach((input) => {
    if (input.value !== state.nodeSearchQuery) {
      input.value = state.nodeSearchQuery;
    }
  });

  const query = state.nodeSearchQuery.trim();
  const results = query ? getNodeSearchResults(query, state.nodeSearchMode) : [];

  el.nodeSearchResults?.forEach((container) => {
    container.innerHTML = "";
    container.classList.toggle("hidden", !isSearchVisible || !query);

    if (!isSearchVisible || !query) {
      return;
    }

    if (results.length === 0) {
      const empty = document.createElement("p");
      empty.className = "node-search-empty";
      empty.textContent = "검색 결과가 없습니다.";
      container.appendChild(empty);
      return;
    }

    results.forEach((node) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "node-search-result";
      if (String(node.id) === String(state.selectedNodeId)) {
        button.classList.add("active");
      }
      button.innerHTML = `
        <span class="node-search-result-title">${escapeHtml(node.title || "제목 없는 노드")}</span>
        <span class="node-search-result-meta">Depth ${escapeHtml(String(node.depth ?? "-"))} / ${escapeHtml(getNodeSearchMatchedLabel(node, query, state.nodeSearchMode))}</span>
      `;
      button.addEventListener("click", () => {
        state.nodeSearchQuery = "";
        selectNode(node.id);
      });
      container.appendChild(button);
    });
  });
}

function getNodeSearchResults(query, mode) {
  const normalizedQuery = normalizeSearchText(query);
  if (!normalizedQuery) {
    return [];
  }

  return state.nodes
    .filter((node) => !isAutoSubtopicSeedNode(node))
    .filter((node) => doesNodeMatchSearch(node, normalizedQuery, mode))
    .sort(compareTreeNodeOrder);
}

function doesNodeMatchSearch(node, normalizedQuery, mode) {
  const question = normalizeSearchText(node.userQuestion);
  const answer = normalizeSearchText(node.aiAnswer);

  if (mode === "question") {
    return question.includes(normalizedQuery);
  }
  if (mode === "answer") {
    return answer.includes(normalizedQuery);
  }
  return question.includes(normalizedQuery) || answer.includes(normalizedQuery);
}

function getNodeSearchMatchedLabel(node, query, mode) {
  const normalizedQuery = normalizeSearchText(query);
  const questionMatched = normalizeSearchText(node.userQuestion).includes(normalizedQuery);
  const answerMatched = normalizeSearchText(node.aiAnswer).includes(normalizedQuery);

  if (mode === "question") {
    return "질문 일치";
  }
  if (mode === "answer") {
    return "답변 일치";
  }
  if (questionMatched && answerMatched) {
    return "질문/답변 일치";
  }
  return questionMatched ? "질문 일치" : "답변 일치";
}

function normalizeSearchText(value) {
  return String(value || "").trim().toLocaleLowerCase("ko-KR");
}

function renderTree() {
  if (!el.treeRoot) {
    return;
  }

  destroyFocusCytoscape();
  el.treeRoot.innerHTML = "";
  el.treeListModeBtn?.classList.toggle("active", state.treeViewMode === "list");
  el.treeGraphModeBtn?.classList.toggle("active", state.treeViewMode === "graph");
  el.treeFocusModeBtn?.classList.toggle("active", state.treeFocusMode);
  if (el.treeFocusModeBtn) {
    el.treeFocusModeBtn.textContent = "전체 화면";
  }
  el.treeFocusCloseBtn?.classList.toggle("hidden", !state.treeFocusMode);
  el.treeRoot.classList.toggle("graph-mode", state.treeViewMode === "graph");
  el.treeRoot.classList.toggle("focus-graph-mode", state.treeFocusMode && state.treeViewMode === "graph");
  el.graphZoomFooter?.classList.toggle("hidden", state.treeViewMode !== "graph");
  if (el.graphZoomLevel) {
    el.graphZoomLevel.textContent = `${Math.round(state.graphZoom * 100)}%`;
  }
  if (el.graphZoomSelect) {
    el.graphZoomSelect.value = state.graphScopeMode === "path"
      ? "path"
      : String(getClosestGraphZoomPreset(state.graphZoom));
  }
  if (el.graphTreeScaleLevel) {
    el.graphTreeScaleLevel.textContent = `${Math.round(state.graphTreeScale * 100)}%`;
  }
  el.graphCircleShapeBtn?.classList.toggle("active", state.graphNodeShape === "circle");
  el.graphBoxShapeBtn?.classList.toggle("active", state.graphNodeShape === "box");
  el.focusMindmapBtn?.classList.toggle("active", state.focusGraphLayout === "mindmap");
  el.focusTreeBtn?.classList.toggle("active", state.focusGraphLayout === "tree");
  el.graphResizeModeBtn?.classList.toggle("active", state.graphResizeMode);
  renderImportantNodeButton();
  renderTreeBuildStatus();

  const treeNodes = getRenderableTreeNodes();
  if (state.treeViewMode === "graph") {
    expandCollapsedAncestorsForNode(state.selectedNodeId, treeNodes);
  }

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

  if (state.treeViewMode === "graph" && state.treeFocusMode && typeof window.cytoscape === "function") {
    renderCytoscapeFocusGraph(treeNodes);
  } else if (state.treeViewMode === "graph") {
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

function renderImportantNodeButton() {
  if (!el.markImportantNodeBtn) {
    return;
  }
  const hasSelectedNode = Boolean(state.selectedNodeId && getNodeById(state.selectedNodeId));
  const isMarked = hasSelectedNode && isImportantNode(state.selectedNodeId);
  el.markImportantNodeBtn.disabled = !hasSelectedNode;
  el.markImportantNodeBtn.textContent = isMarked ? "마크업 해제" : "마크업";
  el.markImportantNodeBtn.classList.toggle("active", isMarked);
}

function isImportantNode(nodeId) {
  return state.importantNodeIds.has(String(nodeId));
}

function toggleSelectedNodeImportant() {
  const selectedNode = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
  if (!selectedNode) {
    return;
  }
  const key = String(selectedNode.id);
  if (state.importantNodeIds.has(key)) {
    state.importantNodeIds.delete(key);
  } else {
    state.importantNodeIds.add(key);
  }
  renderTree();
  renderInsights();
}

function renderTreeList(nodes = state.nodes) {
  const roots = buildTree(nodes).filter((node) => node.parentId === null);
  roots.forEach((node) => el.treeRoot.appendChild(renderTreeNode(node)));
}

function destroyFocusCytoscape() {
  if (focusCytoscape) {
    focusCytoscape.destroy();
    focusCytoscape = null;
  }
}

function getFocusCytoscapeLayout(positions) {
  return {
    name: "preset",
    positions,
    fit: true,
    padding: 96,
    animate: true,
    animationDuration: 260
  };
}

function getFocusMindmapData(visibleNodes, isCircleFocus) {
  const byId = new Map(visibleNodes.map((node) => [String(node.id), node]));
  const childrenById = new Map(visibleNodes.map((node) => [String(node.id), []]));
  visibleNodes.forEach((node) => {
    const parentId = node.parentId == null ? null : String(node.parentId);
    if (parentId && childrenById.has(parentId)) {
      childrenById.get(parentId).push(node);
    }
  });
  childrenById.forEach((children) => children.sort(compareTreeNodeOrder));

  const realRoot = visibleNodes.find((node) => node.parentId == null && Number(node.depth) === 0);
  const selectedRoot = visibleNodes.find((node) => String(node.id) === String(state.selectedNodeId));
  const root = realRoot || selectedRoot || visibleNodes.find((node) => node.parentId == null) || visibleNodes[0];
  if (!root) {
    return { positions: {}, styles: new Map() };
  }

  const rootId = String(root.id);
  const positions = { [rootId]: { x: 0, y: 0 } };
  const styles = new Map();
  const palette = [
    { fill: "#a7e2d8", stroke: "#5aa99b", line: "#678f86" },
    { fill: "#b7dc79", stroke: "#83aa3a", line: "#71834a" },
    { fill: "#f1cf62", stroke: "#bd9440", line: "#8f774b" },
    { fill: "#e88972", stroke: "#bd6555", line: "#80635d" },
    { fill: "#85b5f6", stroke: "#5b86c5", line: "#63778f" },
    { fill: "#e4779d", stroke: "#bb5f82", line: "#86636f" },
    { fill: "#83d0c6", stroke: "#58a398", line: "#668b86" }
  ];
  styles.set(rootId, { fill: "#7c58e8", stroke: "#6542cd", line: "#5b5568", side: 0 });

  const countLeaves = (nodeId) => {
    const children = childrenById.get(nodeId) || [];
    if (!children.length) {
      return 1;
    }
    return children.reduce((total, child) => total + countLeaves(String(child.id)), 0);
  };

  const rootChildIds = new Set();
  const rootChildren = [];
  [...(childrenById.get(rootId) || []), ...visibleNodes.filter((node) => node.parentId == null && String(node.id) !== rootId)].forEach((node) => {
    const nodeId = String(node.id);
    if (!rootChildIds.has(nodeId)) {
      rootChildIds.add(nodeId);
      rootChildren.push(node);
    }
  });
  const rightChildren = [];
  const leftChildren = [];
  rootChildren.forEach((child, index) => {
    (index % 2 === 0 ? rightChildren : leftChildren).push(child);
  });
  if (!rightChildren.length && leftChildren.length > 1) {
    rightChildren.push(leftChildren.shift());
  }

  const xStep = isCircleFocus ? 178 : 230;
  const rootGap = isCircleFocus ? 195 : 260;
  const yStep = isCircleFocus ? 104 : 92;
  const branchGap = isCircleFocus ? 28 : 36;

  const placeSubtree = (node, side, depth, yCenter, ySpan, branchIndex) => {
    const nodeId = String(node.id);
    const x = side * (rootGap + Math.max(0, depth - 1) * xStep);
    positions[nodeId] = { x, y: yCenter };
    const branchStyle = palette[branchIndex % palette.length];
    styles.set(nodeId, { ...branchStyle, side });

    const children = childrenById.get(nodeId) || [];
    if (!children.length) {
      return;
    }

    const totalLeaves = children.reduce((sum, child) => sum + countLeaves(String(child.id)), 0);
    let cursor = yCenter - Math.max(ySpan, totalLeaves * yStep) / 2;
    children.forEach((child) => {
      const leaves = countLeaves(String(child.id));
      const span = Math.max(yStep, leaves * yStep);
      const childY = cursor + span / 2;
      placeSubtree(child, side, depth + 1, childY, span, branchIndex);
      cursor += span + branchGap * 0.35;
    });
  };

  const placeSide = (sideChildren, side, topOffset) => {
    const totalSpan = sideChildren.reduce((sum, child) => sum + countLeaves(String(child.id)) * yStep + branchGap, 0);
    let cursor = topOffset - totalSpan / 2;
    sideChildren.forEach((child) => {
      const branchIndex = rootChildren.findIndex((entry) => String(entry.id) === String(child.id));
      const span = Math.max(yStep, countLeaves(String(child.id)) * yStep);
      const childY = cursor + span / 2;
      placeSubtree(child, side, 1, childY, span, branchIndex);
      cursor += span + branchGap;
    });
  };

  placeSide(rightChildren, 1, 0);
  placeSide(leftChildren, -1, 0);

  return { positions, styles };
}

function getCytoscapeDropTarget(cy, sourceId) {
  const source = cy?.getElementById(String(sourceId));
  if (!source || source.empty()) {
    return null;
  }
  const position = source.renderedPosition();
  return cy.nodes()
    .filter((candidate) => candidate.id() !== String(sourceId))
    .toArray()
    .sort((left, right) => {
      const leftDepth = Number(left.data("depth")) || 0;
      const rightDepth = Number(right.data("depth")) || 0;
      return rightDepth - leftDepth;
    })
    .find((candidate) => {
      const box = candidate.renderedBoundingBox({ includeLabels: false, includeOverlays: false });
      const padding = 18;
      return (
        position.x >= box.x1 - padding &&
        position.x <= box.x2 + padding &&
        position.y >= box.y1 - padding &&
        position.y <= box.y2 + padding
      );
    }) || null;
}

function clearCytoscapeDropState(cy) {
  cy?.nodes().removeClass("dragging drop-target invalid-drop");
}

function renderCytoscapeFocusGraph(nodes = state.nodes) {
  const visibleNodes = getZoomScopedGraphNodes(getVisibleGraphNodes(nodes));
  const hiddenCountMap = getCollapsedHiddenCountMap(nodes);
  const nodeIds = new Set(visibleNodes.map((node) => String(node.id)));
  const css = getComputedStyle(document.body);
  const accent = css.getPropertyValue("--accent").trim() || "#37c6a5";
  const text = css.getPropertyValue("--text").trim() || "#223845";
  const muted = css.getPropertyValue("--muted").trim() || "#668294";
  const surface = css.getPropertyValue("--surface").trim() || "#f8fcfe";
  const nodeStroke = css.getPropertyValue("--node-stroke").trim() || "#6b9bb5";
  const selectedFill = css.getPropertyValue("--node-active-fill").trim() || "#d1f3e8";
  const isCircleFocus = state.graphNodeShape === "circle";
  const renderOptions = getGraphRenderOptions();
  const mindmap = getFocusMindmapData(visibleNodes, isCircleFocus);

  const container = document.createElement("div");
  container.className = `cyto-tree-canvas mindmap-layout ${isCircleFocus ? "circle-layout" : "box-layout"}`;
  container.addEventListener("wheel", (event) => {
    event.preventDefault();
    event.stopPropagation();
  }, { passive: false });
  el.treeRoot.appendChild(container);

  const elements = [
    ...visibleNodes.map((node) => {
      const depth = Number(node.depth) || 0;
      const hiddenCount = hiddenCountMap.get(String(node.id)) || 0;
      const compact = renderOptions.semanticCompact && (renderOptions.labeledDepth < 0 || depth > renderOptions.labeledDepth);
      
      // 💡 [여기 추가] 원형 모드일 때 띄어쓰기가 없어도 글자 중심을 잘라 두 줄(\n)로 강제 분할합니다.
      let nodeTitle = getGraphDisplayTitle(node);
      if (isCircleFocus && !compact && nodeTitle.length > 5) {
        if (nodeTitle.includes(" ")) {
          nodeTitle = nodeTitle.replace(" ", "\n");
        } else {
          const mid = Math.ceil(nodeTitle.length / 2);
          nodeTitle = nodeTitle.slice(0, mid) + "\n" + nodeTitle.slice(mid);
        }
      }

      const label = compact
        ? ""
        : `${nodeTitle}${hiddenCount ? `\n+${hiddenCount} 숨김` : ""}`;
      return {
        group: "nodes",
        data: {
          id: String(node.id),
          label,
          depth,
          compact,
          hiddenCount,
          ...mindmap.styles.get(String(node.id)),
          selected: String(node.id) === String(state.selectedNodeId),
          important: isImportantNode(node.id),
          root: node.parentId == null
        },
        classes: [
          String(node.id) === String(state.selectedNodeId) ? "selected" : "",
          isImportantNode(node.id) ? "important" : "",
          node.parentId == null ? "root" : "",
          hiddenCount ? "collapsed" : "",
          compact ? "compact" : ""
        ].filter(Boolean).join(" ")
      };
    }),
    ...visibleNodes
      .filter((node) => node.parentId != null && nodeIds.has(String(node.parentId)))
      .map((node) => ({
        group: "edges",
        data: {
          id: `${String(node.parentId)}->${String(node.id)}`,
          source: String(node.parentId),
          target: String(node.id),
          color: mindmap.styles.get(String(node.id))?.line || nodeStroke
        }
      }))
  ];

  focusCytoscape = window.cytoscape({
    container,
    elements,
    userZoomingEnabled: true,
    userPanningEnabled: true,
    boxSelectionEnabled: false,
    wheelSensitivity: 0.28,
    minZoom: 0.12,
    maxZoom: 4.2,
    style: [
  {
    selector: "node",
style: {
  shape: isCircleFocus ? "ellipse" : "round-rectangle",
  label: "data(label)",
  "background-color": "data(fill)",
  "border-color": "data(stroke)",
  "border-width": isCircleFocus ? 2.4 : 2,
  color: "#111827",
  "font-size": isCircleFocus ? 13 : 16,
  "font-weight": 900,
  "text-outline-color": "rgba(255,255,255,0.72)",
  "text-outline-width": 1.2,
  
  "text-wrap": "wrap",
  "text-max-width": isCircleFocus ? 68 : 210,
  
  "text-valign": "center",
  "text-halign": "center",
  
  width: isCircleFocus ? 96 : "label",
  height: isCircleFocus ? 96 : "label",
  padding: isCircleFocus ? "6px" : "17px",
  "min-width": isCircleFocus ? 96 : 146,
  "min-height": isCircleFocus ? 96 : 44,
  
  "shadow-blur": 10,
  "shadow-color": "data(stroke)",
  "shadow-opacity": 0.12,
  "shadow-offset-y": 4,
  "overlay-opacity": 0,
  "transition-property": "background-color, border-color, width, height, opacity",
  "transition-duration": "140ms"
}
  },
  {
    selector: "node:selected, node.selected",
    style: {
      "background-color": selectedFill,
      "border-color": accent,
      "border-width": 4,
      color: "#111827",
      "shadow-blur": isCircleFocus ? 24 : 18,
      "shadow-color": accent,
      "shadow-opacity": 0.35,
      "shadow-offset-y": 8
    }
  },
  {
    selector: "node.important",
    style: {
      "border-color": "#f0b94f",
      "border-style": "double",
      "border-width": 4
    }
  },
  {
    selector: "node.compact",
    style: {
      label: "",
      width: isCircleFocus ? 28 : 34,
      height: isCircleFocus ? 28 : 20,
      padding: "0px",
      "min-width": isCircleFocus ? 28 : 34,
      "min-height": isCircleFocus ? 28 : 20,
      "border-width": isCircleFocus ? 2 : 1.6,
      opacity: 0.82
    }
  },
  {
    selector: "node.dragging",
    style: {
      opacity: 0.78,
      "border-style": "dashed",
      "z-index": 90
    }
  },
  {
    selector: "node.drop-target",
    style: {
      "background-color": selectedFill,
      "border-color": accent,
      "border-width": 5,
      "shadow-blur": 22,
      "shadow-color": accent,
      "shadow-opacity": 0.42
    }
  },
  {
    selector: "node.important",
    style: {
      "border-color": "#f0b94f",
      "border-style": "double",
      "border-width": 4
    }
  },
  {
    selector: "node.collapsed",
    style: {
      "background-blacken": -0.05,
      "text-outline-color": surface,
      "text-outline-width": 1
    }
  },
  {
    selector: "edge",
    style: {
      width: 3.2,
      "line-color": "data(color)",
      "target-arrow-color": "data(color)",
      "target-arrow-shape": "none",
      "curve-style": "bezier",
      opacity: 0.78,
      "z-index": 1
    }
  },
  {
    selector: "edge.dragging-edge",
    style: {
      opacity: 0.08
    }
  },
  /* 루트(Root) 노드 스타일 정교화 */
  {
    selector: "node[depth = 0], node.root",
    style: {
      "font-size": isCircleFocus ? 15 : 17,
      "background-color": "#f1eaff",
      "border-color": "#6542cd",
      color: "#111827",
      "border-width": 3,
      "text-outline-color": "rgba(255,255,255,0.82)",
      "text-outline-width": 1.4,
      "text-max-width": isCircleFocus ? 86 : 210,
      ...(isCircleFocus ? { width: 114, height: 114 } : { "min-width": 196, "min-height": 54 })
    }
  }
],
layout: getFocusCytoscapeLayout(mindmap.positions)
});

  let applyingFocusViewport = false;
  focusCytoscape.on("zoom pan", () => {
    if (applyingFocusViewport) {
      return;
    }
    state.focusMindmapViewport = {
      zoom: focusCytoscape.zoom(),
      pan: focusCytoscape.pan()
    };
  });

  focusCytoscape.on("tap", "node", (event) => {
    const nodeId = event.target.id();
    const now = Date.now();
    const isDoubleTap = lastCytoscapeTap.nodeId === nodeId && now - lastCytoscapeTap.time <= 360;
    lastCytoscapeTap = { nodeId, time: now };
    if (isDoubleTap) {
      toggleGraphNodeCollapse(nodeId, nodes);
      return;
    }
    selectNode(nodeId);
  });

  focusCytoscape.on("grab", "node", (event) => {
    const sourceId = event.target.id();
    clearCytoscapeDropState(focusCytoscape);
    event.target.addClass("dragging");
    event.target.connectedEdges().addClass("dragging-edge");
    state.dragState.active = true;
    state.dragState.sourceNodeId = sourceId;
  });

  focusCytoscape.on("drag", "node", (event) => {
    const sourceId = event.target.id();
    focusCytoscape.nodes().removeClass("drop-target invalid-drop");
    const target = getCytoscapeDropTarget(focusCytoscape, sourceId);
    if (!target) {
      return;
    }
    target.addClass(canMoveNodeUnderTarget(sourceId, target.id(), getTreeSourceNodes()) ? "drop-target" : "invalid-drop");
  });

  focusCytoscape.on("free", "node", (event) => {
    const sourceId = event.target.id();
    const target = getCytoscapeDropTarget(focusCytoscape, sourceId);
    event.target.connectedEdges().removeClass("dragging-edge");
    clearCytoscapeDropState(focusCytoscape);
    state.dragState.active = false;
    state.dragState.sourceNodeId = null;
    if (target && canMoveNodeUnderTarget(sourceId, target.id(), getTreeSourceNodes())) {
      commitTreeMove(sourceId, target.id());
    } else {
      focusCytoscape.layout(getFocusCytoscapeLayout(mindmap.positions)).run();
    }
  });

  requestAnimationFrame(() => {
    applyingFocusViewport = true;
    const viewport = state.focusMindmapViewport;
    if (viewport?.zoom && viewport?.pan) {
      focusCytoscape.zoom(clamp(Number(viewport.zoom) || 1, focusCytoscape.minZoom(), focusCytoscape.maxZoom()));
      focusCytoscape.pan({
        x: Number(viewport.pan.x) || 0,
        y: Number(viewport.pan.y) || 0
      });
    } else {
      focusCytoscape.fit(undefined, 96);
      focusCytoscape.center();
    }
    applyingFocusViewport = false;
  });
}

function renderTreeGraph(nodes = state.nodes) {
  const options = getGraphRenderOptions();
  expandCollapsedAncestorsForNode(state.selectedNodeId, state.nodes);
  expandCollapsedAncestorsForNode(state.selectedNodeId, state.treeNodes);
  const graphNodes = getZoomScopedGraphNodes(getVisibleGraphNodes(nodes));
  const hiddenCountMap = getCollapsedHiddenCountMap(nodes);
  const graph = getTreeGraphLayout(graphNodes, options, hiddenCountMap);
  const svgNS = "http://www.w3.org/2000/svg";
  const treeTooltip = getOrCreateTreeTooltip();
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("class", `tree-graph-svg${state.treeFocusMode ? " professional-graph-svg" : ""}`);
  svg.setAttribute("viewBox", `0 0 ${graph.width} ${graph.height}`);
  const viewportWidth = el.treeRoot?.clientWidth || 0;
  const viewportHeight = el.treeRoot?.clientHeight || 0;
  const isNarrowGraphViewport = viewportWidth > 0 && viewportWidth < 420;
  const fitScale = Math.min(
    viewportWidth > 0 ? (viewportWidth * 0.985) / Math.max(graph.width, 1) : 1,
    viewportHeight > 0 ? (viewportHeight * 0.94) / Math.max(graph.height, 1) : 1
  );
  const isMapFitZoom = state.graphZoom <= 1 || (isNarrowGraphViewport && fitScale < state.graphZoom);
  svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
  const baseRenderScale = state.graphZoom <= 1 ? fitScale : Math.max(fitScale, 1);
  const renderedScale = isMapFitZoom
    ? clamp(baseRenderScale * state.graphTreeScale, 0.25, 8)
    : state.graphZoom * state.graphTreeScale;
  const isViewportFitted = renderedScale <= fitScale * 1.02;
  svg.style.width = `${Math.max(160, Math.round(graph.width * renderedScale))}px`;
  svg.style.height = `${Math.max(120, Math.round(graph.height * renderedScale))}px`;

  if (state.treeFocusMode) {
    renderProfessionalGraphBackdrop(svg, graph, svgNS);
  } else {
    renderStandardGraphBackdrop(svg, graph, svgNS);
  }

  graph.links.forEach((link) => {
    const linkElement = state.treeFocusMode
      ? createProfessionalTreeLink(link, svgNS)
      : createStandardTreeLink(link, svgNS);
    if (!state.treeFocusMode) {
      linkElement.setAttribute("stroke", getComputedStyle(document.body).getPropertyValue("--node-stroke").trim() || "#7aa7bf");
      linkElement.setAttribute("stroke-width", "2.4");
      linkElement.setAttribute("opacity", "0.82");
    } else {
      linkElement.setAttribute("stroke", getComputedStyle(document.body).getPropertyValue("--accent").trim() || "#37c6a5");
      linkElement.setAttribute("stroke-width", "3.2");
      linkElement.setAttribute("opacity", "0.96");
    }
    linkElement.dataset.sourceId = String(link.sourceId);
    linkElement.dataset.targetId = String(link.targetId);
    svg.appendChild(linkElement);
  });

  graph.nodes.forEach((node) => {
    const nodeGroup = document.createElementNS(svgNS, "g");
    nodeGroup.setAttribute("class", buildGraphNodeGroupClass(node.id));
    nodeGroup.dataset.nodeId = String(node.id);
    nodeGroup.dataset.depth = String(node.depth || 0);

    const shape = node.visualShape === "box" || node.visualShape === "mini-box"
      ? document.createElementNS(svgNS, "rect")
      : document.createElementNS(svgNS, "ellipse");
    if (node.visualShape === "box" || node.visualShape === "mini-box") {
      shape.setAttribute("x", String(node.x - node.width / 2));
      shape.setAttribute("y", String(node.y - node.height / 2));
      shape.setAttribute("width", String(node.width));
      shape.setAttribute("height", String(node.height));
      const cornerRadius = node.visualShape === "mini-box" ? Math.max(3, Math.round(node.height * 0.24)) : options.cornerRadius;
      shape.setAttribute("rx", String(cornerRadius));
      shape.setAttribute("ry", String(cornerRadius));
    } else {
      shape.setAttribute("cx", String(node.x));
      shape.setAttribute("cy", String(node.y));
      shape.setAttribute("rx", String(node.rx));
      shape.setAttribute("ry", String(node.ry));
    }
    const shapeClass = node.visualShape === "box" || node.visualShape === "mini-box" ? "tree-node-box" : "tree-node-circle";
    shape.setAttribute("class", node.id === state.selectedNodeId ? `${shapeClass} active` : shapeClass);
    const nodeShadow = shape.cloneNode(false);
    nodeShadow.setAttribute("class", node.visualShape === "box" || node.visualShape === "mini-box"
      ? "tree-node-depth-shadow tree-node-depth-shadow-box"
      : "tree-node-depth-shadow tree-node-depth-shadow-circle");
    if (node.visualShape === "box" || node.visualShape === "mini-box") {
      nodeShadow.setAttribute("y", String(node.y - node.height / 2 + 4));
    } else {
      nodeShadow.setAttribute("cy", String(node.y + 4));
    }
    const emphasizeNode = () => {
      shape.classList.add("hovered");
      if (node.visualShape !== "box" && node.visualShape !== "mini-box") {
        const grow = node.id === state.selectedNodeId ? 5 : 3;
        shape.setAttribute("rx", String(node.rx + grow));
        shape.setAttribute("ry", String(node.ry + grow));
      }
    };
    const normalizeNode = () => {
      shape.classList.remove("hovered");
      if (node.visualShape !== "box" && node.visualShape !== "mini-box") {
        shape.setAttribute("rx", String(node.rx));
        shape.setAttribute("ry", String(node.ry));
      }
    };
    attachGraphDragHandlers(nodeGroup, node.id);
    nodeGroup.addEventListener("pointerdown", (event) => handleGraphNodePointerDown(event, node.id, nodes), { capture: true });
    shape.addEventListener("click", (event) => queueGraphNodeClickSelect(node.id, event));
    shape.addEventListener("mouseenter", (event) => {
      emphasizeNode();
      handleTreeDragHover(node.id);
      showTreeTooltip(treeTooltip, node.title, event);
    });
    shape.addEventListener("mousemove", (event) => moveTreeTooltip(treeTooltip, event));
    shape.addEventListener("mouseleave", () => {
      normalizeNode();
      handleTreeDragHover(null);
      hideTreeTooltip(treeTooltip);
    });
    nodeGroup.appendChild(nodeShadow);
    nodeGroup.appendChild(shape);

    if (node.showLabel) {
      const label = document.createElementNS(svgNS, "text");
      label.setAttribute("x", String(node.x));
      label.setAttribute("y", String(getGraphLabelStartY(node, options)));
      label.setAttribute("class", "tree-node-label");
      label.style.fontSize = `${node.fontSize || options.fontSize}px`;
      if (node.visualShape === "box") {
        node.lines.forEach((line, index) => {
          const tspan = document.createElementNS(svgNS, "tspan");
          tspan.setAttribute("x", String(node.x));
          tspan.setAttribute("dy", index === 0 ? "0" : String(node.lineHeight || options.lineHeight));
          tspan.textContent = line;
          label.appendChild(tspan);
        });
      } else {
        label.textContent = node.lines?.[0] || getCircleNodeTitle(node.title, options.circleLabelLength);
      }
      label.style.pointerEvents = "auto";
      label.style.cursor = "pointer";
      label.addEventListener("click", (event) => queueGraphNodeClickSelect(node.id, event));
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
    }

    if (isImportantNode(node.id)) {
      const marker = document.createElementNS(svgNS, "text");
      marker.setAttribute("x", String(node.x - node.width / 2 + 10));
      marker.setAttribute("y", String(node.y - node.height / 2 + 16));
      marker.setAttribute("class", "tree-node-important-marker");
      marker.textContent = "★";
      nodeGroup.appendChild(marker);
    }

    if (node.hiddenCount > 0) {
      const badge = document.createElementNS(svgNS, "g");
      badge.setAttribute("class", "tree-node-hidden-badge");
      badge.setAttribute("transform", `translate(${node.x + node.width / 2 - 7}, ${node.y - node.height / 2 + 7})`);

      const badgeCircle = document.createElementNS(svgNS, "circle");
      badgeCircle.setAttribute("r", "11");
      badgeCircle.setAttribute("class", "tree-node-hidden-badge-bg");

      const badgeLabel = document.createElementNS(svgNS, "text");
      badgeLabel.setAttribute("class", "tree-node-hidden-badge-label");
      badgeLabel.setAttribute("y", "4");
      badgeLabel.textContent = `+${node.hiddenCount}`;

      badge.appendChild(badgeCircle);
      badge.appendChild(badgeLabel);
      nodeGroup.appendChild(badge);
    }
    if (state.graphResizeMode && String(node.id) === String(state.selectedNodeId)) {
      [
        { mode: "width", x: node.x + node.width / 2, y: node.y, className: " horizontal" },
        { mode: "height", x: node.x, y: node.y + node.height / 2, className: " vertical" },
        { mode: "both", x: node.x + node.width / 2, y: node.y + node.height / 2, className: " corner" }
      ].forEach((handleConfig) => {
        const handle = document.createElementNS(svgNS, "circle");
        handle.setAttribute("cx", String(handleConfig.x));
        handle.setAttribute("cy", String(handleConfig.y));
        handle.setAttribute("r", "4.5");
        handle.setAttribute("class", `tree-node-resize-handle${handleConfig.className}`);
        handle.addEventListener("pointerdown", (event) => startGraphNodeResize(event, node, handleConfig.mode));
        nodeGroup.appendChild(handle);
      });
    }
    svg.appendChild(nodeGroup);
  });

  const canvas = document.createElement("div");
  canvas.className = `tree-graph-canvas ${state.treeFocusMode ? "professional-graph-canvas" : ""} ${isViewportFitted ? "fit-view" : "zoom-view"}`.trim();
  canvas.appendChild(svg);
  el.treeRoot.appendChild(canvas);
  focusSelectedGraphNodeWhenZoomed(graph);
}

function renderProfessionalGraphBackdrop(svg, graph, svgNS) {
  const defs = document.createElementNS(svgNS, "defs");
  const gradient = document.createElementNS(svgNS, "linearGradient");
  gradient.setAttribute("id", "professionalGraphLinkGradient");
  gradient.setAttribute("x1", "0%");
  gradient.setAttribute("x2", "100%");
  gradient.setAttribute("y1", "0%");
  gradient.setAttribute("y2", "0%");

  const start = document.createElementNS(svgNS, "stop");
  start.setAttribute("offset", "0%");
  start.setAttribute("stop-color", "currentColor");
  start.setAttribute("stop-opacity", "0.35");

  const end = document.createElementNS(svgNS, "stop");
  end.setAttribute("offset", "100%");
  end.setAttribute("stop-color", "currentColor");
  end.setAttribute("stop-opacity", "0.82");

  gradient.appendChild(start);
  gradient.appendChild(end);
  defs.appendChild(gradient);
  svg.appendChild(defs);

  const maxDepth = Math.max(...graph.nodes.map((node) => Number(node.depth) || 0), 0);
  for (let depth = 0; depth <= maxDepth; depth += 1) {
    const nodesAtDepth = graph.nodes.filter((node) => Number(node.depth) === depth);
    if (!nodesAtDepth.length) {
      continue;
    }
    const minY = Math.min(...nodesAtDepth.map((node) => node.y - node.height / 2));
    const maxY = Math.max(...nodesAtDepth.map((node) => node.y + node.height / 2));
    const band = document.createElementNS(svgNS, "rect");
    band.setAttribute("x", "12");
    band.setAttribute("y", String(Math.max(6, minY - 18)));
    band.setAttribute("width", String(Math.max(0, graph.width - 24)));
    band.setAttribute("height", String(Math.max(36, maxY - minY + 36)));
    band.setAttribute("rx", "18");
    band.setAttribute("class", depth % 2 === 0 ? "professional-depth-band" : "professional-depth-band alt");
    svg.appendChild(band);
  }
}

function renderStandardGraphBackdrop(svg, graph, svgNS) {
  const defs = document.createElementNS(svgNS, "defs");

  const glow = document.createElementNS(svgNS, "filter");
  glow.setAttribute("id", "standardGraphNodeGlow");
  glow.setAttribute("x", "-35%");
  glow.setAttribute("y", "-35%");
  glow.setAttribute("width", "170%");
  glow.setAttribute("height", "170%");

  const drop = document.createElementNS(svgNS, "feDropShadow");
  drop.setAttribute("dx", "0");
  drop.setAttribute("dy", "5");
  drop.setAttribute("stdDeviation", "4");
  drop.setAttribute("flood-color", "currentColor");
  drop.setAttribute("flood-opacity", "0.18");
  glow.appendChild(drop);
  defs.appendChild(glow);
  svg.appendChild(defs);

  const rootNode = graph.nodes.find((node) => Number(node.depth) === 0);
  if (rootNode) {
    const halo = document.createElementNS(svgNS, "ellipse");
    halo.setAttribute("cx", String(rootNode.x));
    halo.setAttribute("cy", String(rootNode.y));
    halo.setAttribute("rx", String(Math.max(rootNode.width * 1.2, 86)));
    halo.setAttribute("ry", String(Math.max(rootNode.height * 1.1, 46)));
    halo.setAttribute("class", "tree-root-halo");
    svg.appendChild(halo);
  }
}

function createStandardTreeLink(link, svgNS) {
  const path = document.createElementNS(svgNS, "path");
  const verticalDistance = Math.max(20, Math.abs(link.y2 - link.y1));
  const midY = link.y1 + verticalDistance * 0.5;
  path.setAttribute("d", `M ${link.x1} ${link.y1} C ${link.x1} ${midY}, ${link.x2} ${midY}, ${link.x2} ${link.y2}`);
  path.setAttribute("class", "tree-link tree-link-polished");
  path.setAttribute("fill", "none");
  path.setAttribute("stroke-linecap", "round");
  path.setAttribute("stroke-linejoin", "round");
  return path;
}

function createProfessionalTreeLink(link, svgNS) {
  const path = document.createElementNS(svgNS, "path");
  const midY = link.y1 + (link.y2 - link.y1) * 0.52;
  path.setAttribute("d", `M ${link.x1} ${link.y1} C ${link.x1} ${midY}, ${link.x2} ${midY}, ${link.x2} ${link.y2}`);
  path.setAttribute("class", "tree-link professional-tree-link");
  path.setAttribute("fill", "none");
  path.setAttribute("stroke-linecap", "round");
  path.setAttribute("stroke-linejoin", "round");
  return path;
}

function appendProfessionalNodeDetails(nodeGroup, node, svgNS) {
  const badge = document.createElementNS(svgNS, "g");
  badge.setAttribute("class", "professional-depth-badge");
  badge.setAttribute("transform", `translate(${node.x - node.width / 2 + 12}, ${node.y - node.height / 2 + 10})`);

  const badgeBg = document.createElementNS(svgNS, "rect");
  badgeBg.setAttribute("width", "46");
  badgeBg.setAttribute("height", "17");
  badgeBg.setAttribute("rx", "8.5");
  badgeBg.setAttribute("class", "professional-depth-badge-bg");

  const badgeLabel = document.createElementNS(svgNS, "text");
  badgeLabel.setAttribute("x", "23");
  badgeLabel.setAttribute("y", "12");
  badgeLabel.setAttribute("class", "professional-depth-badge-label");
  badgeLabel.textContent = `D${Number(node.depth) || 0}`;

  badge.appendChild(badgeBg);
  badge.appendChild(badgeLabel);
  nodeGroup.appendChild(badge);
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

function focusSelectedGraphNodeWhenZoomed(graph) {
  if (!el.treeRoot || state.treeViewMode !== "graph" || (state.graphZoom <= 1 && state.graphTreeScale <= 1)) {
    return;
  }
  const selected = graph.nodes.find((node) => String(node.id) === String(state.selectedNodeId));
  if (!selected) {
    return;
  }

  const viewportWidth = el.treeRoot.clientWidth || 0;
  const viewportHeight = el.treeRoot.clientHeight || 0;
  const fitScale = Math.min(
    viewportWidth > 0 ? (viewportWidth * 0.985) / Math.max(graph.width, 1) : 1,
    viewportHeight > 0 ? (viewportHeight * 0.94) / Math.max(graph.height, 1) : 1
  );
  const baseRenderScale = state.graphZoom <= 1 ? fitScale : Math.max(fitScale, 1);
  const zoom = (state.graphZoom <= 1 ? baseRenderScale : Number(state.graphZoom) || 1) * (Number(state.graphTreeScale) || 1);
  requestAnimationFrame(() => {
    if (viewportWidth < 420) {
      return;
    }
    el.treeRoot.scrollLeft = Math.max(0, selected.x * zoom - viewportWidth / 2);
    el.treeRoot.scrollTop = Math.max(0, selected.y * zoom - viewportHeight * 0.42);
  });
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

function clearPendingGraphNodeClick() {
  if (graphNodeClickTimer) {
    clearTimeout(graphNodeClickTimer);
    graphNodeClickTimer = null;
  }
}

function queueGraphNodeClickSelect(nodeId, event) {
  if (state.dragState.active || event?.detail > 1) {
    return;
  }

  clearPendingGraphNodeClick();
  graphNodeClickTimer = setTimeout(() => {
    graphNodeClickTimer = null;
    if (!state.dragState.active && state.treeViewMode === "graph") {
      selectNode(nodeId);
    }
  }, 340);
}

function startGraphNodeResize(event, node, mode = "both") {
  event.preventDefault();
  event.stopPropagation();
  event.stopImmediatePropagation();
  clearPendingGraphNodeClick();

  const nodeId = String(node.id);
  const startX = event.clientX;
  const startY = event.clientY;
  const startSize = getGraphNodeSize(nodeId);
  const startWidth = Math.max(Number(node.width) || 1, 1);
  const startHeight = Math.max(Number(node.height) || 1, 1);

  const onMove = (moveEvent) => {
    const deltaX = moveEvent.clientX - startX;
    const deltaY = moveEvent.clientY - startY;
    const nextSize = { ...startSize };
    if (mode === "width" || mode === "both") {
      nextSize.width = clamp(Number((startSize.width * (1 + deltaX / startWidth)).toFixed(2)), 0.65, 7);
    }
    if (mode === "height" || mode === "both") {
      nextSize.height = clamp(Number((startSize.height * (1 + deltaY / startHeight)).toFixed(2)), 0.65, 4);
    }
    state.graphNodeSizeById.set(getGraphNodeSizeKey(nodeId), nextSize);
    renderTree();
  };

  const onUp = () => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}

function handleGraphNodePointerDown(event, nodeId, nodes = state.nodes) {
  if (event.button !== 0) {
    return;
  }
  if (event.target?.classList?.contains("tree-node-resize-handle")) {
    return;
  }

  const now = Date.now();
  const key = String(nodeId);
  const dx = Math.abs(event.clientX - lastGraphNodePointerDown.x);
  const dy = Math.abs(event.clientY - lastGraphNodePointerDown.y);
  const isDoublePointer = (
    lastGraphNodePointerDown.nodeId === key &&
    now - lastGraphNodePointerDown.time <= 420 &&
    dx <= 8 &&
    dy <= 8
  );

  if (!isDoublePointer) {
    lastGraphNodePointerDown = {
      nodeId: key,
      time: now,
      x: event.clientX,
      y: event.clientY
    };
    return;
  }

  event.preventDefault();
  event.stopImmediatePropagation();
  clearPendingGraphNodeClick();
  lastGraphNodePointerDown = {
    nodeId: null,
    time: 0,
    x: 0,
    y: 0
  };
  toggleGraphNodeCollapse(key, nodes);
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
  if (isImportantNode(node.id)) {
    button.classList.add("important");
  }
  button.draggable = false;

  const time = new Date(node.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const importantMark = isImportantNode(node.id) ? `<span class="node-important-mark">★</span>` : "";
  // 뒤쪽의 <span class="node-meta"> 영역을 지우고 깔끔하게 제목(title)만 렌더링합니다.
button.innerHTML = `<span class="node-title">${importantMark}${escapeHtml(node.title)}</span>`;
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
  if (isImportantNode(nodeId)) {
    classes.push("important");
  }
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

function getCollapsedHiddenCountMap(nodes = state.nodes) {
  const nodeIds = new Set(nodes.map((node) => String(node.id)));
  const countMap = new Map();

  [...state.collapsedGraphNodeIds].forEach((nodeId) => {
    if (!nodeIds.has(String(nodeId))) {
      state.collapsedGraphNodeIds.delete(nodeId);
      return;
    }
    const hiddenCount = Math.max(0, collectSubtreeIds(nodeId, nodes).size - 1);
    if (hiddenCount > 0) {
      countMap.set(String(nodeId), hiddenCount);
    } else {
      state.collapsedGraphNodeIds.delete(nodeId);
    }
  });

  return countMap;
}

function getVisibleGraphNodes(nodes = state.nodes) {
  if (!state.collapsedGraphNodeIds.size) {
    return nodes;
  }

  const hiddenIds = new Set();
  state.collapsedGraphNodeIds.forEach((nodeId) => {
    collectSubtreeIds(nodeId, nodes).forEach((subtreeId) => {
      if (String(subtreeId) !== String(nodeId)) {
        hiddenIds.add(String(subtreeId));
      }
    });
  });

  return nodes.filter((node) => !hiddenIds.has(String(node.id)));
}

function getZoomScopedGraphNodes(nodes = state.nodes) {
  if (state.graphScopeMode === "path" && state.selectedNodeId) {
    const pathIds = new Set(getPathToNode(state.selectedNodeId).map((node) => String(node.id)));
    return nodes
      .filter((node) => pathIds.has(String(node.id)))
      .map((node) => ({
        ...node,
        parentId: node.parentId != null && pathIds.has(String(node.parentId)) ? node.parentId : null
      }));
  }

  if (state.graphZoom <= 1 || !state.selectedNodeId) {
    return nodes;
  }

  const selectedId = String(state.selectedNodeId);
  if (!nodes.some((node) => String(node.id) === selectedId)) {
    return nodes;
  }

  const radius = state.graphZoom >= 1.4 ? 0 : (state.graphZoom >= 1.3 ? 1 : (state.graphZoom >= 1.2 ? 2 : 3));
  const neighbors = new Map();
  nodes.forEach((node) => {
    const nodeId = String(node.id);
    if (!neighbors.has(nodeId)) {
      neighbors.set(nodeId, new Set());
    }
    if (node.parentId != null && nodes.some((entry) => String(entry.id) === String(node.parentId))) {
      const parentId = String(node.parentId);
      if (!neighbors.has(parentId)) {
        neighbors.set(parentId, new Set());
      }
      neighbors.get(nodeId).add(parentId);
      neighbors.get(parentId).add(nodeId);
    }
  });

  const includedIds = new Set([selectedId]);
  const queue = [{ id: selectedId, distance: 0 }];
  while (queue.length) {
    const current = queue.shift();
    if (current.distance >= radius) {
      continue;
    }
    (neighbors.get(current.id) || []).forEach((nextId) => {
      if (includedIds.has(nextId)) {
        return;
      }
      includedIds.add(nextId);
      queue.push({ id: nextId, distance: current.distance + 1 });
    });
  }

  return nodes
    .filter((node) => includedIds.has(String(node.id)))
    .map((node) => ({
      ...node,
      parentId: node.parentId != null && includedIds.has(String(node.parentId)) ? node.parentId : null
    }));
}

function toggleGraphNodeCollapse(nodeId, nodes = state.nodes) {
  if (state.dragState.active) {
    return;
  }

  const key = String(nodeId);
  const subtreeIds = collectSubtreeIds(key, nodes);
  if (subtreeIds.size <= 1) {
    return;
  }

  if (state.collapsedGraphNodeIds.has(key)) {
    state.collapsedGraphNodeIds.delete(key);
  } else {
    state.collapsedGraphNodeIds.add(key);
    if (state.selectedNodeId && subtreeIds.has(String(state.selectedNodeId)) && String(state.selectedNodeId) !== key) {
      state.selectedNodeId = key;
    }
  }
  render();
}

function expandCollapsedAncestorsForNode(nodeId, nodes = state.nodes) {
  if (!nodeId || !state.collapsedGraphNodeIds.size) {
    return false;
  }

  const nodeMap = new Map(nodes.map((node) => [String(node.id), node]));
  let cursor = nodeMap.get(String(nodeId));
  let expanded = false;

  while (cursor?.parentId != null) {
    const parentId = String(cursor.parentId);
    if (state.collapsedGraphNodeIds.delete(parentId)) {
      expanded = true;
    }
    cursor = nodeMap.get(parentId);
  }

  return expanded;
}

function transferGraphNodeUiState(fromNodeId, toNodeId) {
  const fromKey = String(fromNodeId);
  const toKey = String(toNodeId);
  ["circle", "box"].forEach((shape) => {
    const fromSizeKey = getGraphNodeSizeKey(fromKey, shape);
    const toSizeKey = getGraphNodeSizeKey(toKey, shape);
    if (state.graphNodeSizeById.has(fromSizeKey)) {
      state.graphNodeSizeById.set(toSizeKey, state.graphNodeSizeById.get(fromSizeKey));
      state.graphNodeSizeById.delete(fromSizeKey);
    }
  });
  if (state.collapsedGraphNodeIds.delete(fromKey)) {
    state.collapsedGraphNodeIds.add(toKey);
  }
  if (state.importantNodeIds.delete(fromKey)) {
    state.importantNodeIds.add(toKey);
  }
}

function deleteGraphNodeSizeState(nodeId) {
  ["circle", "box"].forEach((shape) => {
    state.graphNodeSizeById.delete(getGraphNodeSizeKey(nodeId, shape));
  });
}

function applyTreeMutationLocally(mutation) {
  if (!mutation?.type) {
    return false;
  }
  const collapsedIdsToClear = mutation.type === "delete_subtree"
    ? collectSubtreeIds(mutation.nodeId, getTreeSourceNodes())
    : new Set();

  if (isCurrentRoomLocal()) {
    let localNextNodes = state.nodes.map(cloneNode);
    if (mutation.type === "delete_subtree") {
      const ids = collectSubtreeIds(mutation.nodeId, localNextNodes);
      localNextNodes = localNextNodes.filter((node) => !ids.has(node.id));
    } else if (mutation.type === "move_subtree") {
      const sourceNode = localNextNodes.find((node) => node.id === mutation.nodeId);
      const targetNode = localNextNodes.find((node) => node.id === mutation.newParentId);
      if (!sourceNode || !targetNode) {
        return false;
      }
      if (!canMoveNodeUnderTarget(mutation.nodeId, mutation.newParentId, localNextNodes)) {
        return false;
      }
      sourceNode.parentId = mutation.newParentId;
      sourceNode.depth = (Number(targetNode.depth) || 0) + 1;
      normalizeSubtreeDepths(localNextNodes, sourceNode.id);
    }

    state.nodes = localNextNodes;
    state.treeNodes = localNextNodes.map(cloneNode);
    if (mutation.type === "delete_subtree") {
      const selectedStillExists = state.selectedNodeId && localNextNodes.some((node) => node.id === state.selectedNodeId);
      if (!selectedStillExists) {
        state.selectedNodeId = mutation.fallbackSelectedNodeId || null;
      }
      collapsedIdsToClear.forEach((nodeId) => {
        state.collapsedGraphNodeIds.delete(String(nodeId));
        state.importantNodeIds.delete(String(nodeId));
        deleteGraphNodeSizeState(nodeId);
      });
    }
    persistCurrentLocalConversationState();
    render();
    return true;
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
    collapsedIdsToClear.forEach((nodeId) => {
      state.collapsedGraphNodeIds.delete(String(nodeId));
      state.importantNodeIds.delete(String(nodeId));
      deleteGraphNodeSizeState(nodeId);
    });
  }

  render();
  return true;
}

async function onDeleteSelectedNode() {
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
  const targetNodeId = selected.id;
  const localRoom = getLocalConversationRoom();
  const effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;

  // 1. 화면에서 먼저 지웁니다 (Optimistic UI)
  applyTreeMutationLocally({
    type: "delete_subtree",
    nodeId: targetNodeId,
    fallbackSelectedNodeId
  });

  // 2. 백엔드에도 지워달라고 비동기로 요청합니다
  if (!isCurrentRoomLocal()) {
    try {
      const token = state.currentSession?.accessToken || "";
      await deleteNodeApi(effectiveRoomId, targetNodeId, token);
      console.log(`✅ [백엔드 연동] 노드(${targetNodeId}) 삭제 완료!`);
    } catch (error) {
      alert(`서버에서 노드를 삭제하는 중 오류가 발생했습니다: ${toUiError(error)}`);
      console.error("노드 삭제 실패:", error);
      // 실패하면 다시 데이터를 불러와서 복구하는 로직을 넣어도 좋습니다.
    }
  }
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
    if (event.detail > 1) {
      clearPendingGraphNodeClick();
      return;
    }
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
      moveEvent.preventDefault();
      clearPendingGraphNodeClick();
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

async function commitTreeMove(sourceNodeId, targetNodeId) {
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

  const localRoom = getLocalConversationRoom();
  const effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;

  // 1. 화면에서 먼저 이사시킵니다 (Optimistic UI)
  applyTreeMutationLocally({
    type: "move_subtree",
    nodeId: String(sourceNodeId),
    newParentId: String(targetNodeId)
  });
  
  clearTreeDragState();
  renderTree();

  // 2. 백엔드에도 새 집 주소를 알려줍니다
  if (!isCurrentRoomLocal()) {
    try {
      const token = state.currentSession?.accessToken || "";
      await moveNodeApi(effectiveRoomId, sourceNodeId, targetNodeId, token);
      console.log(`✅ [백엔드 연동] 노드 이동 완료 (새 부모: ${targetNodeId})`);
    } catch (error) {
      alert(`서버에서 노드를 이동하는 중 오류가 발생했습니다: ${toUiError(error)}`);
      console.error("노드 이동 실패:", error);
    }
  }
}

function renderChat() {
  if (!el.chatFeed) {
    return;
  }

  el.chatFeed.innerHTML = "";

  const pathNodes = state.selectedNodeId ? getPathToNode(state.selectedNodeId) : [];
  let selectedBubble = null;
  pathNodes.forEach((node) => {
    const isSelected = String(node.id) === String(state.selectedNodeId);
    if (isAutoSubtopicSeedNode(node) && !isSelected) {
      return;
    }
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

  const room = getVisibleConversationRooms().find((r) => r.id === state.currentRoomId);
  const selected = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
  const roomLabel = room
    ? (room.localOnly ? `Rebuilt: ${room.title}` : `Room: ${room.title}`)
    : "Room: 선택 없음";
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
  persistCurrentLocalConversationSelection();
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
  const isLocalRoom = isCurrentRoomLocal();

  if (!node) {
    if (el.selectedNodeTitle) el.selectedNodeTitle.textContent = "선택 없음";
    if (el.selectedNodeMeta) el.selectedNodeMeta.textContent = "Depth - / Parent -";
    if (el.rebuildConversationBtn) el.rebuildConversationBtn.disabled = true;
    if (el.markImportantNodeBtn) el.markImportantNodeBtn.disabled = true;
    if (el.deleteSelectedNodeBtn) el.deleteSelectedNodeBtn.disabled = true;
    if (el.treeEditHint) el.treeEditHint.textContent = "노드를 다른 노드 위로 드래그하면 해당 노드의 자식으로 이동합니다.";
    if (el.depthBar) {
      el.depthBar.style.width = "10%";
      el.depthBar.style.background = "var(--control-active-bg)";
    }
    if (el.driftAlert) {
      el.driftAlert.textContent = "질문을 입력하면 학습 경로가 시작됩니다.";
      el.driftAlert.className = "alert";
    }
    renderBranchNotice(null);
    renderConversationSummaryPlaceholder("선택한 노드의 질문/답변 1쌍을 핵심 어구로 요약합니다.");
    state.insightRequestToken++;
    renderChildRecommendationPlaceholder("선택 노드의 하위 주제를 추천합니다.");
    state.childRecommendationRequestToken++;
    return;
  }

  const parentTitle = getParentTitleForNode(node);
  const canEditTree = state.treeBuildStatus !== "processing";

  el.selectedNodeTitle.textContent = node.title || "선택 노드";
  el.selectedNodeMeta.textContent = `Depth ${node.depth} / Parent: ${parentTitle}`;
  if (el.rebuildConversationBtn) {
    el.rebuildConversationBtn.disabled = isLocalRoom;
  }
  if (el.deleteSelectedNodeBtn) {
    el.deleteSelectedNodeBtn.disabled = !canEditTree;
  }
  renderImportantNodeButton();
  if (el.treeEditHint) {
    if (!canEditTree) {
      el.treeEditHint.textContent = "트리 구성 중에는 편집할 수 없습니다.";
    } else if (isLocalRoom) {
      el.treeEditHint.textContent = "재구성 대화에서도 노드 이동, 삭제, 추가 질문이 가능합니다.";
    } else if (!node.parentId) {
      el.treeEditHint.textContent = "루트 노드는 드래그 이동할 수 없지만 삭제는 가능합니다.";
    } else {
      el.treeEditHint.textContent = "노드를 다른 노드 위로 드래그하면 해당 노드의 자식으로 이동합니다.";
    }
  }

  applyInsightDepthUi(node.depth, node);
  renderBranchNotice(node);
  void renderChildNodeRecommendations(node, isLocalRoom);

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

function buildChildRecommendationCacheKey(nodeId) {
  return `child:${roomIdSafe(state.currentRoomId)}:${String(nodeId)}`;
}

function normalizeRecommendationParentTitle(parentTitle) {
  let normalized = String(parentTitle || "").replace(/\s+/g, " ").trim();
  const trailingParentContextPattern = /\s*\([^()]*\)\s*$/;
  while (trailingParentContextPattern.test(normalized)) {
    normalized = normalized.replace(trailingParentContextPattern, "").trim();
  }
  return normalized || "상위 노드";
}

function normalizeChildRecommendationItems(items, parentTitle) {
  const source = Array.isArray(items) ? items : [];
  const seen = new Set();
  const normalized = [];
  const normalizedParentTitle = normalizeRecommendationParentTitle(parentTitle);

  source.forEach((item) => {
    if (normalized.length >= 3) {
      return;
    }
    const subtopic = String(item || "").replace(/\s+/g, " ").trim();
    if (!subtopic) {
      return;
    }
    const dedupeKey = subtopic.toLowerCase();
    if (seen.has(dedupeKey)) {
      return;
    }
    seen.add(dedupeKey);
    normalized.push({
      id: `rec_${normalized.length + 1}`,
      subtopic,
      displayLabel: `${normalizedParentTitle} - ${subtopic}`,
      used: false,
      isCreating: false,
      createdNodeId: null
    });
  });

  return normalized.slice(0, 3);
}

function renderChildRecommendationPlaceholder(message) {
  if (!el.childNodeRecommendationList) {
    return;
  }
  const text = String(message || "").trim() || "하위 노드 추천을 준비 중입니다.";
  el.childNodeRecommendationList.innerHTML = `<p class="child-recommendation-placeholder">${escapeHtml(text)}</p>`;
}

function renderChildRecommendationButtons(nodeId, cacheEntry) {
  if (!el.childNodeRecommendationList) {
    return;
  }

  const options = Array.isArray(cacheEntry?.options) ? cacheEntry.options : [];
  if (!options.length) {
    renderChildRecommendationPlaceholder("추천 가능한 하위 주제가 없습니다.");
    return;
  }

  el.childNodeRecommendationList.innerHTML = "";
  options.forEach((option) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "child-recommendation-btn";
    if (option.used) {
      button.classList.add("used");
    }
    if (option.isCreating) {
      button.classList.add("loading");
    }

    const disabledByProcessing = state.treeBuildStatus === "processing";
    button.disabled = Boolean(option.used || option.isCreating || disabledByProcessing);
    const buttonText = option.displayLabel || option.subtopic || "";
    button.textContent = option.isCreating ? `${buttonText} (생성 중...)` : buttonText;
    button.title = buttonText;
    button.addEventListener("click", () => {
      void onChildRecommendationClick(nodeId, option.id);
    });
    el.childNodeRecommendationList.appendChild(button);
  });
}

async function renderChildNodeRecommendations(node, isLocalRoom) {
  if (!node || !el.childNodeRecommendationList) {
    return;
  }
  if (!state.currentSession?.accessToken) {
    renderChildRecommendationPlaceholder("로그인 후 하위 노드 추천을 사용할 수 있습니다.");
    return;
  }

  const localRoom = isLocalRoom ? getLocalConversationRoom() : null;
  const effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;
  if (!effectiveRoomId) {
    renderChildRecommendationPlaceholder("대화방이 선택되지 않았습니다.");
    return;
  }

  const cacheKey = buildChildRecommendationCacheKey(node.id);
  const cached = state.childRecommendationCache.get(cacheKey);
  if (cached) {
    renderChildRecommendationButtons(node.id, cached);
    return;
  }

  renderChildRecommendationPlaceholder("하위 노드 후보를 불러오는 중...");
  if (state.pendingChildRecommendationKeys.has(cacheKey)) {
    return;
  }

  const requestToken = ++state.childRecommendationRequestToken;
  state.pendingChildRecommendationKeys.add(cacheKey);

  try {
    const payload = await getChildNodeRecommendationsApi(
      effectiveRoomId,
      node.id,
      state.currentSession?.accessToken || ""
    );
    const options = normalizeChildRecommendationItems(payload?.recommendations, node.title);
    const cacheEntry = {
      nodeId: String(node.id),
      options
    };
    state.childRecommendationCache.set(cacheKey, cacheEntry);

    if (requestToken !== state.childRecommendationRequestToken) {
      return;
    }

    const currentNode = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
    if (!currentNode || String(currentNode.id) !== String(node.id)) {
      return;
    }

    renderChildRecommendationButtons(node.id, cacheEntry);
  } catch (error) {
    if (requestToken !== state.childRecommendationRequestToken) {
      return;
    }
    renderChildRecommendationPlaceholder("하위 노드 후보를 불러오지 못했습니다.");
  } finally {
    state.pendingChildRecommendationKeys.delete(cacheKey);
  }
}

async function onChildRecommendationClick(nodeId, optionId) {
  const cacheKey = buildChildRecommendationCacheKey(nodeId);
  const cacheEntry = state.childRecommendationCache.get(cacheKey);
  const option = cacheEntry?.options?.find((item) => item.id === optionId);
  const parentNode = getNodeById(String(nodeId));
  if (!cacheEntry || !option || !parentNode) {
    return;
  }
  if (option.used || option.isCreating) {
    return;
  }
  if (state.treeBuildStatus === "processing") {
    alert("트리 구성 중에는 하위 노드를 생성할 수 없습니다.");
    return;
  }

  option.isCreating = true;
  renderChildRecommendationButtons(nodeId, cacheEntry);

  try {
    const createdNodeId = await createChildNodeFromRecommendation(parentNode, option.subtopic || option.label);
    option.used = true;
    option.createdNodeId = createdNodeId ? String(createdNodeId) : null;
  } catch (error) {
    alert(`하위 노드 생성 실패: ${toUiError(error)}`);
  } finally { // 💡 [긴급 교정] 기존에 final { 로 되어있던 부분을 finally { 로 수정해주세요!
    option.isCreating = false;
    const selectedNode = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
    if (selectedNode && String(selectedNode.id) === String(nodeId)) {
      renderChildRecommendationButtons(nodeId, cacheEntry);
    }
  }
}

async function createChildNodeFromRecommendation(parentNode, subtopic) {
  const normalizedSubtopic = String(subtopic || "").replace(/\s+/g, " ").trim();
  if (!normalizedSubtopic) {
    throw new Error("하위 노드 주제가 비어 있습니다.");
  }

  const localRoom = getLocalConversationRoom();
  const effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;
  if (!effectiveRoomId) {
    throw new Error("대화방이 선택되지 않았습니다.");
  }

  const parentTitle = normalizeRecommendationParentTitle(parentNode?.title);
  const question = buildRecommendedChildQuestion(parentTitle, normalizedSubtopic);
  const nextNodeTitle = buildRecommendedChildTreeTitle(parentTitle, normalizedSubtopic);
  const parentId = String(parentNode.id);
  const nextDepth = Number(parentNode.depth || 0) + 1;
  const isNewBranch = hasVisibleChildNode(parentId);
  const depthLead = getDepthLeadAgainstOtherLeaves(parentNode, nextDepth);
  const tempId = `rec_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
  const previousSelectedNodeId = state.selectedNodeId;

  state.nodes.push({
    id: tempId,
    parentId,
    title: nextNodeTitle,
    userQuestion: question,
    aiAnswer: "응답 생성 중...",
    depth: nextDepth,
    timestamp: Date.now()
  });
  const tempNode = getNodeById(tempId);
  const immediateFocusNotice = buildRouteFocusNotice(tempNode);
  if (immediateFocusNotice) {
    setRouteNotice({
      ...immediateFocusNotice,
      nodeId: tempId,
      createdAt: Date.now()
    });
    clearBranchNotice();
  } else if (isNewBranch) {
    setBranchNotice({
      nodeId: tempId,
      message: buildDeepBranchNoticeMessage({ depth: nextDepth, isNewBranch, depthLead }),
      createdAt: Date.now()
    });
  }
  state.selectedNodeId = tempId;
  expandCollapsedAncestorsForNode(tempId, state.nodes);

  if (isCurrentRoomLocal()) {
    state.treeNodes = state.nodes.map(cloneNode);
    persistCurrentLocalConversationState();
  }
  render();

  try {
    const response = await createRecommendedChildNodeApi({
      roomId: effectiveRoomId,
      nodeId: parentId,
      subtopic: normalizedSubtopic,
      token: state.currentSession?.accessToken || ""
    });

    const persistedNodeId = applyAssistantResponseToTempNode({
      tempId,
      response,
      question,
      parentId,
      nextDepth
    });
    rememberPlacementCheck(persistedNodeId, parentId, question);

    if (isCurrentRoomLocal()) {
      state.treeNodes = state.nodes.map(cloneNode);
      if (persistedNodeId && state.nodes.some((node) => node.id === persistedNodeId)) {
        state.selectedNodeId = persistedNodeId;
      }
      detectPlacementChangeNotice(persistedNodeId);
      persistCurrentLocalConversationState();
      render();
      return persistedNodeId;
    }

    const applied = await loadRoomHistoryWithOptions(state.currentRoomId, { keepTreeWhileProcessing: false });
    if (applied && persistedNodeId && state.nodes.some((node) => node.id === persistedNodeId)) {
      state.selectedNodeId = persistedNodeId;
      expandCollapsedAncestorsForNode(persistedNodeId, state.nodes);
      expandCollapsedAncestorsForNode(persistedNodeId, state.treeNodes);
      detectPlacementChangeNotice(persistedNodeId);
    }
    render();
    return persistedNodeId;
  } catch (error) {
    state.nodes = state.nodes.filter((node) => node.id !== tempId);
    state.selectedNodeId = previousSelectedNodeId;
    if (isCurrentRoomLocal()) {
      state.treeNodes = state.nodes.map(cloneNode);
      persistCurrentLocalConversationState();
    }
    render();
    throw error;
  }
}

function buildRecommendedChildQuestion(parentTitle, subtopic) {
  return `${parentTitle}과 관련하여, ${subtopic}에 대해 알려줘`;
}

function buildRecommendedChildTreeTitle(parentTitle, subtopic) {
  return `${subtopic} (${parentTitle})`;
}

function getParentTitleForNode(node) {
  const parentNode = node?.parentId ? getNodeById(node.parentId) : null;
  return parentNode ? parentNode.title : "없음 (최상위)";
}

function rememberPlacementCheck(nodeId, requestedParentId, question) {
  if (!nodeId) {
    return;
  }
  state.pendingPlacementChecks.set(String(nodeId), {
    requestedParentId: requestedParentId != null ? String(requestedParentId) : null,
    question: String(question || ""),
    createdAt: Date.now()
  });
}

function detectPlacementChangeNotice(nodeId) {
  const key = String(nodeId || "");
  const check = state.pendingPlacementChecks.get(key);
  const node = key ? getNodeById(key) : null;
  if (!check || !node) {
    return;
  }
  if (isInitialTopicNode(node)) {
    state.pendingPlacementChecks.delete(key);
    return;
  }
  if (state.treeBuildStatus === "processing") {
    return;
  }

  const actualParentId = node.parentId != null ? String(node.parentId) : null;
  scheduleRootTopicCheckForPlacement(check, node, actualParentId);
  if (actualParentId !== check.requestedParentId) {
    const actualParent = actualParentId ? getNodeById(actualParentId) : null;
    clearDepthRouteNoticeForNode(key);
    setBranchNotice({
      nodeId: key,
      message: `질문이 '${actualParent?.title || "다른 경로"}' 분기로 이동했습니다.`,
      createdAt: Date.now()
    });
    state.pendingPlacementChecks.delete(key);
    return;
  }

  const focusMetrics = getRouteFocusMetrics(node);
  if (focusMetrics.shouldNotify) {
    setRouteNotice({
      nodeId: key,
      type: "info",
      kind: focusMetrics.isDeepening ? "deepening" : "leaf-bias",
      message: buildFocusDepthStatusMessage(focusMetrics),
      createdAt: Date.now()
    });
    if (actualParentId && countVisibleChildren(actualParentId) >= 2) {
      setBranchNotice({
        nodeId: key,
        message: "새 분기가 생성되었습니다. 같은 부모 아래 별도 흐름으로 나뉘었습니다.",
        createdAt: Date.now()
      });
    } else {
      clearBranchNotice();
    }
    state.pendingPlacementChecks.delete(key);
    return;
  }

  if (actualParentId && countVisibleChildren(actualParentId) >= 2) {
    clearDepthRouteNoticeForNode(key);
    setBranchNotice({
      nodeId: key,
      message: "새 분기가 생성되었습니다. 같은 부모 아래 별도 흐름으로 나뉘었습니다.",
      createdAt: Date.now()
    });
    state.pendingPlacementChecks.delete(key);
    return;
  }

  if (Date.now() - Number(check.createdAt || 0) > 60000) {
    state.pendingPlacementChecks.delete(key);
  }
}

function scheduleRootTopicCheckForPlacement(check, node, actualParentId) {
  if (!check || !node) {
    return;
  }
  if (state.rootTopicNoticeNodeIds.has(String(node.id))) {
    return;
  }
  const localRoom = getLocalConversationRoom();
  const effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;
  const parentIdForCheck = actualParentId || check.requestedParentId;
  schedulePostAnswerRootTopicCheck({
    roomId: effectiveRoomId,
    parentId: parentIdForCheck,
    question: check.question,
    nodeId: node.id,
    skip: !parentIdForCheck
  });
}

function clearDepthRouteNoticeForNode(nodeId) {
  if (
    isDepthRouteNoticeKind(state.routeNotice?.kind) &&
    String(state.routeNotice.nodeId) === String(nodeId)
  ) {
    clearRouteNotice();
  }
}

function countVisibleChildren(parentId) {
  if (!parentId) {
    return 0;
  }
  return state.nodes.filter((node) => (
    String(node.parentId || "") === String(parentId) &&
    !isAutoSubtopicSeedNode(node)
  )).length;
}

function setBranchNotice(notice) {
  if (!notice?.nodeId || !notice?.message) {
    clearBranchNotice();
    return;
  }
  state.branchNotice = {
    nodeId: String(notice.nodeId),
    message: String(notice.message),
    createdAt: Number(notice.createdAt) || Date.now()
  };
}

function clearBranchNotice() {
  state.branchNotice = null;
}

function transferBranchNoticeNodeId(fromNodeId, toNodeId) {
  if (!state.branchNotice || !fromNodeId || !toNodeId) {
    return;
  }
  if (String(state.branchNotice.nodeId) === String(fromNodeId)) {
    state.branchNotice = {
      ...state.branchNotice,
      nodeId: String(toNodeId)
    };
  }
}

function loadAcceptedRootTopicNodeKeys() {
  try {
    const raw = localStorage.getItem(ROOT_TOPIC_ACCEPTED_STORAGE_KEY);
    const parsed = JSON.parse(raw || "[]");
    return new Set(Array.isArray(parsed) ? parsed.map(String) : []);
  } catch (error) {
    return new Set();
  }
}

function saveAcceptedRootTopicNodeKeys(keys) {
  try {
    localStorage.setItem(ROOT_TOPIC_ACCEPTED_STORAGE_KEY, JSON.stringify([...keys].map(String)));
  } catch (error) {
    console.warn("Failed to persist accepted root topic notices:", error);
  }
}

function buildRootTopicAcceptedNodeKey(nodeId) {
  return `${String(state.currentRoomId || "")}:${String(nodeId || "")}`;
}

function hasAcceptedRootTopicNotice(nodeId) {
  return state.acceptedRootTopicNodeKeys.has(buildRootTopicAcceptedNodeKey(nodeId));
}

function rememberAcceptedRootTopicNotice(nodeId) {
  if (!nodeId) {
    return;
  }
  state.acceptedRootTopicNodeKeys.add(buildRootTopicAcceptedNodeKey(nodeId));
  saveAcceptedRootTopicNodeKeys(state.acceptedRootTopicNodeKeys);
}

function transferRootTopicPreflightResult(fromNodeId, toNodeId) {
  if (!fromNodeId || !toNodeId || String(fromNodeId) === String(toNodeId)) {
    return;
  }
  const result = state.rootTopicPreflightResults.get(String(fromNodeId));
  if (!result) {
    return;
  }
  state.rootTopicPreflightResults.delete(String(fromNodeId));
  state.rootTopicPreflightResults.set(String(toNodeId), result);
}

function getActiveBranchNotice(node) {
  if (!node || !state.branchNotice) {
    return null;
  }
  const isSameNode = String(state.branchNotice.nodeId) === String(node.id);
  const isFresh = Date.now() - Number(state.branchNotice.createdAt || 0) < 60000;
  return isSameNode && isFresh ? state.branchNotice : null;
}

function renderBranchNotice(node) {
  if (!el.branchAlert) {
    return;
  }
  const notice = getActiveBranchNotice(node);
  el.branchAlert.classList.toggle("hidden", !notice);
  el.branchAlert.textContent = notice?.message || "";
}

function maybeHandleAssistantOutOfScopeNotice(response, nodeId, question, originalParentId) {
  if (!nodeId || !isAssistantOutOfScopeAnswer(response?.answer)) {
    return false;
  }

  const key = String(nodeId);
  state.rootTopicNoticeNodeIds.add(key);
  const rootTopic = getRootTopicLabelForNode(key);
  setRouteNotice({
    nodeId: key,
    type: "strong",
    kind: "topic",
    message: `대주제 '${rootTopic}'와 관계가 낮은 노드가 생성되었습니다.`,
    createdAt: Date.now()
  });
  clearBranchNotice();
  renderInsights();
  void openRootTopicDecisionDialog({
    unrelated: true,
    rootTopic,
    similarity: 0
  }).then(async (choice) => {
    if (choice === "continue") {
      await forceKeepNodeUnderOriginalParent({
        nodeId: key,
        parentId: originalParentId,
        question
      });
      return;
    }
    if (choice !== "new_room") {
      return;
    }
    try {
      const currentNode = getNodeById(key);
      const nextQuestion = currentNode?.userQuestion || question || "";
      const localRoom = getLocalConversationRoom();
      const sourceRoomId = localRoom?.sourceRoomId || state.currentRoomId;
      await startNewRoomWithQuestion(nextQuestion, { removeNodeId: key, sourceRoomId });
    } catch (error) {
      setAuthMessage(`새 대화방 생성 실패: ${toUiError(error)}`, "error");
      render();
    }
  });
  return true;
}

function isAssistantOutOfScopeAnswer(answer) {
  const text = String(answer || "").replace(/\s+/g, " ").trim();
  if (!text) {
    return false;
  }
  return /이탈\s*방지|대주제(?:와|에서)?\s*(?:다른|벗어난|이탈)|관련(?:이|은)?\s*(?:없는|없습니다|없으며|없고)|학습\s*목표(?:에서|를)?\s*벗어난|학습\s*목표(?:와는|와)?\s*(?:조금\s*)?다른\s*주제|원래(?:의)?\s*학습\s*목표.*다른\s*주제|학습\s*목표와\s*관련|직접(?:적)?으로\s*관련(?:이)?\s*없|범위(?:를)?\s*벗어난|주제(?:가)?\s*아닙니다/.test(text);
}

function getRootTopicLabelForNode(nodeId) {
  const path = getPathToNode(nodeId);
  const root = path.find((node) => !node.parentId) || path[0] || state.nodes.find((node) => !node.parentId);
  return root?.title || "현재 대화";
}

function getRootTopicLabelForParent(parent) {
  const root = getRootNodeForNode(parent) || state.nodes.find((node) => !node.parentId);
  return root?.title || "현재 대화";
}

function shouldUseLocalRootTopicMismatchNotice(question, parent) {
  // 특정 주제명을 하드코딩하지 않고, 현재 루트/경로 텍스트와의 일반 토큰 관계만 보조 확인한다.
  // 최종 판단은 백엔드 root-topic-check가 우선이고, 이 함수는 명백한 무관 질문을 놓치지 않기 위한 안전장치다.
  return isQuestionOutsideRootTopic(question, parent);
}

function countDirectRootTopicChildren(root) {
  if (!root) {
    return 0;
  }
  return state.nodes.filter((node) => (
    String(node.parentId || "") === String(root.id) &&
    !isAutoSubtopicSeedNode(node)
  )).length;
}

function collectRootContextTokens(root, pathIds = new Set()) {
  const tokens = new Set();
  const addText = (text) => {
    extractMeaningfulTokens(text).forEach((token) => tokens.add(token));
  };

  addText(root?.title);
  addText(root?.userQuestion);
  addText(getCurrentRoomTitle());

  state.nodes.forEach((node) => {
    if (!node || isAutoSubtopicSeedNode(node)) {
      return;
    }
    if (String(node.id) === String(root?.id)) {
      return;
    }
    if (!isDescendantNodeOf(node, root)) {
      return;
    }
    const depth = Number(node.depth || 0);
    if (pathIds.has(String(node.id)) && depth > 1) {
      return;
    }
    if (depth > 2) {
      return;
    }
    addText(node.title);
    addText(node.userQuestion);
  });

  return tokens;
}

function isDescendantNodeOf(node, ancestor) {
  if (!node || !ancestor) {
    return false;
  }
  let cursor = node;
  const guard = new Set();
  while (cursor?.parentId != null && !guard.has(String(cursor.id))) {
    guard.add(String(cursor.id));
    if (String(cursor.parentId) === String(ancestor.id)) {
      return true;
    }
    cursor = getNodeById(cursor.parentId);
  }
  return false;
}

function handleLocalRootTopicMismatchNotice(nodeId, question, originalParentId) {
  const key = String(nodeId);
  const node = getNodeById(key);
  if (!node) {
    return;
  }
  state.rootTopicNoticeNodeIds.add(key);
  const rootTopic = getRootTopicLabelForNode(key);
  setRouteNotice({
    nodeId: key,
    type: "strong",
    kind: "topic",
    message: `대주제 '${rootTopic}'와 관계가 낮은 노드가 생성되었습니다.`,
    createdAt: Date.now()
  });
  clearBranchNotice();
  renderInsights();
  void openRootTopicDecisionDialog({
    unrelated: true,
    rootTopic,
    similarity: 0
  }).then(async (choice) => {
    if (choice === "continue") {
      await forceKeepNodeUnderOriginalParent({
        nodeId: key,
        parentId: originalParentId,
        question
      });
      return;
    }
    if (choice !== "new_room") {
      return;
    }
    try {
      const nextQuestion = node.userQuestion || question || "";
      const localRoom = getLocalConversationRoom();
      const sourceRoomId = localRoom?.sourceRoomId || state.currentRoomId;
      await startNewRoomWithQuestion(nextQuestion, { removeNodeId: key, sourceRoomId });
    } catch (error) {
      setAuthMessage(`새 대화방 생성 실패: ${toUiError(error)}`, "error");
      render();
    }
  });
}

async function forceKeepNodeUnderOriginalParent({ nodeId, parentId, question }) {
  const key = String(nodeId || "");
  const node = getNodeById(key);
  if (!key || !node) {
    return;
  }

  const targetParentId = parentId != null ? String(parentId) : null;
  const nextTitle = summarizeTitle(question || node.userQuestion || node.title || "");
  const targetParent = targetParentId ? getNodeById(targetParentId) : null;
  const nextDepth = targetParent ? Number(targetParent.depth || 0) + 1 : 0;

  node.parentId = targetParentId;
  node.title = nextTitle;
  node.depth = nextDepth;
  normalizeSubtreeDepths(state.nodes, key);
  state.treeNodes = state.nodes.map(cloneNode);
  state.selectedNodeId = key;
  clearBranchNotice();
  render();

  if (isCurrentRoomLocal()) {
    persistCurrentLocalConversationState();
    return;
  }

  const localRoom = getLocalConversationRoom();
  const effectiveRoomId = localRoom?.sourceRoomId || state.currentRoomId;
  if (!effectiveRoomId) {
    return;
  }

  try {
    if (state.treeBuildStatus === "processing") {
      await waitForTreeProcessingToFinish(effectiveRoomId, key);
    }
    await forceNodePlacementApi(
      effectiveRoomId,
      key,
      targetParentId,
      nextTitle,
      state.currentSession?.accessToken || ""
    );
    await loadRoomHistoryWithOptions(effectiveRoomId, { keepTreeWhileProcessing: false });
    if (state.nodes.some((item) => String(item.id) === key)) {
      state.selectedNodeId = key;
    }
    render();
  } catch (error) {
    setAuthMessage(`노드 위치 고정 실패: ${toUiError(error)}`, "error");
    render();
  }
}

function beginRootTopicPreflight({ roomId, parentId, question, nodeId, skip = false }) {
  if (skip || !roomId || !parentId || !question || !nodeId) {
    return;
  }
  void checkRootTopicApi({
    roomId,
    parentId,
    message: question,
    rootTopic: getRootTopicLabelForNode(parentId),
    token: state.currentSession?.accessToken || ""
  }).then((result) => {
    const targetNode = getNodeById(nodeId) || [...state.nodes].reverse().find((node) => node.userQuestion === question);
    if (!result?.unrelated || !targetNode) {
      return;
    }
    const rootTopic = result.rootTopic || "현재 대화";
    state.rootTopicPreflightResults.set(String(targetNode.id), {
      unrelated: true,
      rootTopic,
      similarity: Number(result.similarity) || 0,
      createdAt: Date.now()
    });
    setRouteNotice({
      nodeId: targetNode.id,
      type: "strong",
      kind: "topic",
      message: `대주제 '${rootTopic}'와 관계가 낮아 보입니다. 답변 후 진행 방식을 선택할 수 있습니다.`,
      createdAt: Date.now()
    });
    clearBranchNotice();
    renderInsights();
  }).catch((error) => {
    console.warn("Pre-answer root topic check failed:", error);
  });
}

function schedulePostAnswerRootTopicCheck({ roomId, parentId, question, nodeId, skip = false }) {
  if (skip || !roomId || !parentId || !question || !nodeId) {
    return;
  }
  if (state.rootTopicNoticeNodeIds.has(String(nodeId))) {
    return;
  }
  const preflightResult = state.rootTopicPreflightResults.get(String(nodeId));
  if (preflightResult?.unrelated && getNodeById(nodeId)) {
    state.rootTopicPreflightResults.delete(String(nodeId));
    state.rootTopicNoticeNodeIds.add(String(nodeId));
    const rootTopic = preflightResult.rootTopic || "현재 대화";
    setRouteNotice({
      nodeId,
      type: "strong",
      kind: "topic",
      message: `대주제 '${rootTopic}'와 관계가 낮은 노드가 생성되었습니다.`,
      createdAt: Date.now()
    });
    clearBranchNotice();
    renderInsights();
    void openRootTopicDecisionDialog({
      unrelated: true,
      rootTopic,
      similarity: Number(preflightResult.similarity) || 0
    }).then(async (choice) => {
      if (choice !== "new_room") {
        return;
      }
      try {
        const currentNode = getNodeById(nodeId);
        const nextQuestion = currentNode?.userQuestion || question;
        await startNewRoomWithQuestion(nextQuestion, { removeNodeId: nodeId, sourceRoomId: roomId });
      } catch (error) {
        setAuthMessage(`새 대화방 생성 실패: ${toUiError(error)}`, "error");
        render();
      }
    });
    return;
  }
  void checkRootTopicApi({
    roomId,
    parentId,
    message: question,
    rootTopic: getRootTopicLabelForNode(parentId),
    token: state.currentSession?.accessToken || ""
  }).then((result) => {
    if (!result?.unrelated || !getNodeById(nodeId)) {
      return;
    }
    const rootTopic = result.rootTopic || "현재 대화";
    setRouteNotice({
      nodeId,
      type: "strong",
      kind: "topic",
      message: `대주제 '${rootTopic}'와 관계가 낮은 노드가 생성되었습니다.`,
      createdAt: Date.now()
    });
    clearBranchNotice();
    renderInsights();
    return openRootTopicDecisionDialog({
      unrelated: true,
      rootTopic,
      similarity: Number(result.similarity) || 0
    }).then(async (choice) => {
      if (choice !== "new_room") {
        return;
      }
      try {
        const currentNode = getNodeById(nodeId);
        const nextQuestion = currentNode?.userQuestion || question;
        await startNewRoomWithQuestion(nextQuestion, { removeNodeId: nodeId, sourceRoomId: roomId });
      } catch (error) {
        setAuthMessage(`새 대화방 생성 실패: ${toUiError(error)}`, "error");
        render();
      }
    });
  }).catch((error) => {
    console.warn("Post-answer root topic check failed:", error);
  });
}

function handleDeferredRootTopicNotice(response, nodeId) {
  if (!response?.rootTopicUnrelated || !nodeId) {
    return;
  }
  const rootTopic = response.rootTopic || "현재 대화";
  setRouteNotice({
    nodeId,
    type: "strong",
    kind: "topic",
    message: `대주제 '${rootTopic}'와 관계가 낮은 노드가 생성되었습니다.`,
    createdAt: Date.now()
  });
  clearBranchNotice();
  void openRootTopicDecisionDialog({
    unrelated: true,
    rootTopic,
    similarity: Number(response.rootTopicSimilarity) || 0
  }).then(async (choice) => {
    if (choice !== "new_room") {
      return;
    }
    try {
      const currentNode = getNodeById(nodeId);
      const question = currentNode?.userQuestion || "";
      const localRoom = getLocalConversationRoom();
      const sourceRoomId = localRoom?.sourceRoomId || state.currentRoomId;
      await startNewRoomWithQuestion(question, { removeNodeId: nodeId, sourceRoomId });
    } catch (error) {
      setAuthMessage(`새 대화방 생성 실패: ${toUiError(error)}`, "error");
      render();
    }
  });
}

async function startNewRoomWithQuestion(question, { removeNodeId = null, sourceRoomId = null } = {}) {
  const nextQuestion = String(question || "").trim();
  if (!nextQuestion) {
    return;
  }
  const token = state.currentSession?.accessToken || "";
  if (removeNodeId && sourceRoomId) {
    try {
      await deleteNodeApi(sourceRoomId, removeNodeId, token);
    } catch (error) {
      console.warn("Failed to remove unrelated node before starting new room:", error);
    }
  }

  clearRouteNotice();
  clearBranchNotice();
  const newRoomId = await createRoomApi(summarizeRoomTitle(nextQuestion), token);
  clearPendingTreeMutations();
  state.treeProcessingWatcherToken++;
  state.currentRoomId = Number(newRoomId);
  state.nodes = [];
  state.treeNodes = [];
  state.selectedNodeId = null;
  state.suppressRootTopicCheckOnce = false;
  state.forceCreateUnrelatedOnce = false;
  await refreshRoomsOnly();
  if (el.chatInput) {
    el.chatInput.value = nextQuestion;
  }
  render();
  setChatSubmitBusy(false);
  setTimeout(() => el.chatForm?.requestSubmit(), 0);
}

async function resolveRootTopicDecision({ roomId, parentId, question }) {
  if (!roomId || !parentId || state.suppressRootTopicCheckOnce) {
    state.suppressRootTopicCheckOnce = false;
    return "continue";
  }

  try {
    const result = await checkRootTopicApi({
      roomId,
      parentId,
      message: question,
      rootTopic: getRootTopicLabelForNode(parentId),
      token: state.currentSession?.accessToken || ""
    });
    if (!result?.unrelated) {
      return "continue";
    }
    setRouteNotice({
      nodeId: parentId,
      type: "strong",
      kind: "topic",
      message: `대주제 '${result.rootTopic || "현재 대화"}'와 관계가 낮습니다.`,
      createdAt: Date.now()
    });
    renderInsights();
    const choice = await openRootTopicDecisionDialog(result);
    if (choice === "continue") {
      state.pendingRouteNoticePlan = {
        type: "strong",
        kind: "topic",
        message: `대주제 '${result.rootTopic || "현재 대화"}'와 관계가 낮은 노드를 생성했습니다.`,
        createdAt: Date.now()
      };
    }
      return choice === "continue" ? "continue_unrelated" : choice;
  } catch (error) {
    console.warn("Root topic check failed:", error);
    return "check_failed";
  }
}

function openRootTopicDecisionDialog(result) {
  return new Promise((resolve) => {
    const backdrop = document.createElement("div");
    backdrop.className = "route-decision-backdrop";
    const rootTopic = escapeHtml(result?.rootTopic || "현재 대주제");
    backdrop.innerHTML = `
      <div class="route-decision-card" role="dialog" aria-modal="true">
        <strong>대주제와 다른 질문으로 보여요</strong>
        <p>이 질문은 <b>${rootTopic}</b>와 관계가 낮습니다. 현재 경로에 노드를 만들까요, 새 대화방에서 시작할까요?</p>
        <div class="route-decision-actions">
          <button type="button" class="btn btn-ghost" data-choice="continue">원래 노드의 자식으로 생성</button>
          <button type="button" class="btn btn-primary" data-choice="new_room">새 대화방 만들기</button>
          <button type="button" class="btn btn-ghost" data-choice="cancel">취소</button>
        </div>
      </div>
    `;

    const finish = (choice) => {
      backdrop.remove();
      resolve(choice);
    };

    backdrop.addEventListener("click", (event) => {
      const choice = event.target?.dataset?.choice;
      if (choice) {
        finish(choice);
      } else if (event.target === backdrop) {
        finish("cancel");
      }
    });
    document.body.appendChild(backdrop);
    backdrop.querySelector("[data-choice='new_room']")?.focus();
  });
}

function parseRootTopicReject(error) {
  const message = error instanceof Error ? error.message : String(error || "");
  if (!message.includes("ROOT_TOPIC_UNRELATED")) {
    return null;
  }
  const parts = message.split("|");
  return {
    unrelated: true,
    rootTopic: parts[1] || "현재 대주제",
    similarity: Number(parts[2]) || 0,
    message: "대주제와 관계가 낮은 질문으로 보입니다."
  };
}

function setRouteNotice(notice) {
  if (!notice?.nodeId || !notice?.message) {
    clearRouteNotice();
    return;
  }
  if (notice.kind === "branch") {
    setBranchNotice(notice);
    return;
  }
  state.routeNotice = {
    nodeId: String(notice.nodeId),
    type: notice.type === "strong" ? "strong" : "info",
    kind: normalizeRouteNoticeKind(notice.kind),
    message: String(notice.message),
    createdAt: Number(notice.createdAt) || Date.now()
  };
}

function normalizeRouteNoticeKind(kind) {
  if (["depth", "deepening", "leaf-bias", "depth-mixed", "topic"].includes(kind)) {
    return kind;
  }
  return "topic";
}

function clearRouteNotice() {
  state.routeNotice = null;
}

function transferRouteNoticeNodeId(fromNodeId, toNodeId) {
  if (!state.routeNotice || !fromNodeId || !toNodeId) {
    return;
  }
  if (String(state.routeNotice.nodeId) === String(fromNodeId)) {
    state.routeNotice = {
      ...state.routeNotice,
      nodeId: String(toNodeId)
    };
  }
}

function getActiveRouteNotice(node) {
  if (!node || !state.routeNotice) {
    return null;
  }
  const isSameNode = String(state.routeNotice.nodeId) === String(node.id);
  const isFresh = Date.now() - Number(state.routeNotice.createdAt || 0) < 45000;
  if (!isSameNode || !isFresh) {
    return null;
  }
  if (isDepthRouteNoticeKind(state.routeNotice.kind) && !isDepthNoticeStillValidForNode(node)) {
    return null;
  }
  return state.routeNotice;
}

function isDepthRouteNoticeKind(kind) {
  return kind === "depth" || kind === "deepening" || kind === "leaf-bias" || kind === "depth-mixed";
}

function isDepthNoticeStillValidForNode(node) {
  return getRouteFocusMetrics(node).shouldNotify;
}

function isInitialTopicNode(node) {
  if (!node) {
    return true;
  }
  if (!node.parentId || Number(node.depth || 0) <= 1) {
    return true;
  }
  return isAutoSubtopicSeedNode(node);
}

function getDepthLeadForExistingNode(node) {
  if (!node || isInitialTopicNode(node)) {
    return 0;
  }
  const selectedDepth = Number(node.depth || 0);
  const pathIds = new Set(getPathForRouteMetrics(node).map((pathNode) => String(pathNode.id)));
  const deepestOutsideLeafDepth = getDeepestLeafDepthOutsidePath(pathIds);
  if (deepestOutsideLeafDepth == null) {
    return 0;
  }
  return Math.max(0, selectedDepth - deepestOutsideLeafDepth);
}

function getPathForRouteMetrics(node) {
  if (!node) {
    return [];
  }
  const existingPath = getPathToNode(node.id);
  if (existingPath.length) {
    return existingPath;
  }
  const parentPath = node.parentId ? getPathToNode(node.parentId) : [];
  return [...parentPath, node];
}

function getRouteFocusMetrics(node) {
  if (!node || isInitialTopicNode(node)) {
    return {
      focusDepth: 0,
      depthLead: 0,
      score: 0,
      shouldNotify: false,
      isDeepening: false,
      isLeafBiased: false,
      branchAnchorTitle: ""
    };
  }

  const path = getPathForRouteMetrics(node);
  const depthLead = getDepthLeadForExistingNode(node);
  const branchAnchorIndex = findNearestBranchAnchorIndex(path);
  const focusDepth = Math.max(0, path.length - branchAnchorIndex - 1);
  const score = Math.max(focusDepth, depthLead);
  const isDeepening = focusDepth >= 5;
  const isLeafBiased = depthLead >= 3;

  return {
    focusDepth,
    depthLead,
    score,
    shouldNotify: isDeepening || isLeafBiased,
    isDeepening,
    isLeafBiased,
    branchAnchorTitle: path[branchAnchorIndex]?.title || "현재 주제"
  };
}

function findNearestBranchAnchorIndex(path) {
  if (!Array.isArray(path) || path.length <= 1) {
    return 0;
  }

  for (let index = path.length - 2; index >= 0; index -= 1) {
    if (countAllChildren(path[index].id) >= 2) {
      return index;
    }
  }

  return 0;
}

function countAllChildren(parentId) {
  if (!parentId) {
    return 0;
  }
  return state.nodes.filter((node) => String(node.parentId || "") === String(parentId)).length;
}

function buildRouteNoticeForPendingQuestion({ question, parent, nextDepth, isNewBranch, depthLead, skipRootTopicCheck = false }) {
  if (!skipRootTopicCheck && isQuestionOutsideRootTopic(question, parent)) {
    return {
      type: "strong",
      kind: "topic",
      message: "루트 주제와 다른 질문으로 보여요. 새 대화방을 만들어 진행하는 것을 권장합니다.",
      createdAt: Date.now()
    };
  }

  const projectedNode = parent
    ? {
        id: "__pending__",
        parentId: parent.id,
        depth: nextDepth,
        title: question,
        userQuestion: question
      }
    : null;
  const focusNotice = buildRouteFocusNotice(projectedNode);
  if (focusNotice) {
    return {
      ...focusNotice,
      createdAt: Date.now()
    };
  }

  if (!isNewBranch) {
    return null;
  }

  return {
    type: "info",
    kind: "branch",
    message: buildDeepBranchNoticeMessage({ depth: nextDepth, isNewBranch, depthLead }),
    createdAt: Date.now()
  };
}

function buildRouteFocusNotice(node) {
  const metrics = getRouteFocusMetrics(node);
  if (!metrics.shouldNotify) {
    return null;
  }
  return {
    type: "info",
    kind: metrics.isDeepening && metrics.isLeafBiased
      ? "depth-mixed"
      : (metrics.isDeepening ? "deepening" : "leaf-bias"),
    message: buildFocusDepthStatusMessage(metrics)
  };
}

function buildDeepBranchNoticeMessage({ depth, isNewBranch, depthLead }) {
  if (isNewBranch && depthLead >= 3) {
    return `새 분기이며 다른 리프보다 ${depthLead}단계 깊습니다. 경로를 나눠 확인하세요.`;
  }
  if (isNewBranch) {
    return `새 분기가 생성되었습니다. Depth ${depth}에서 별도 흐름으로 이어집니다.`;
  }
  return `현재 경로가 다른 리프보다 ${depthLead}단계 깊어졌습니다. 흐름을 확인하세요.`;
}

function hasVisibleChildNode(parentId) {
  if (!parentId) {
    return false;
  }
  return state.nodes.some((node) => (
    String(node.parentId || "") === String(parentId) &&
    !isAutoSubtopicSeedNode(node)
  ));
}

function getDepthLeadAgainstOtherLeaves(parent, nextDepth) {
  if (!parent) {
    return 0;
  }

  const currentPathIds = new Set(getPathToNode(parent.id).map((node) => String(node.id)));
  const deepestOutsideLeafDepth = getDeepestLeafDepthOutsidePath(currentPathIds);
  if (deepestOutsideLeafDepth == null) {
    return 0;
  }

  return nextDepth - deepestOutsideLeafDepth;
}

function getDeepestLeafDepthOutsidePath(pathIds) {
  const childCounts = new Map();
  state.nodes.forEach((node) => {
    if (node.parentId != null) {
      const parentId = String(node.parentId);
      childCounts.set(parentId, (childCounts.get(parentId) || 0) + 1);
    }
  });

  let deepest = null;
  state.nodes.forEach((node) => {
    const nodeId = String(node.id);
    if (pathIds.has(nodeId)) {
      return;
    }
    if ((childCounts.get(nodeId) || 0) > 0) {
      return;
    }
    const depth = Number(node.depth);
    if (!Number.isFinite(depth)) {
      return;
    }
    deepest = deepest == null ? depth : Math.max(deepest, depth);
  });

  return deepest;
}

function isQuestionOutsideRootTopic(question, parent) {
  if (!question || !parent || state.nodes.length < 2) {
    return false;
  }

  const root = getRootNodeForNode(parent) || state.nodes.find((node) => !node.parentId);
  if (!root) {
    return false;
  }

  const rootText = [
    root.title,
    root.userQuestion,
    getCurrentRoomTitle(),
    ...collectRootContextTokens(root, new Set(getPathToNode(parent.id).map((node) => String(node.id))))
  ].filter(Boolean).join(" ");
  const pathText = getPathToNode(parent.id)
    .map((node) => `${node.title || ""} ${node.userQuestion || ""}`)
    .join(" ");
  const rootSimilarity = textTokenSimilarity(question, rootText);
  const pathSimilarity = textTokenSimilarity(question, pathText);

  return rootSimilarity < 0.08 && pathSimilarity < 0.08 && extractMeaningfulTokens(question).length >= 1;
}

function getRootNodeForNode(node) {
  const path = node?.id ? getPathToNode(node.id) : [];
  return path.length ? path[0] : null;
}

function getCurrentRoomTitle() {
  const room = getVisibleConversationRooms().find((entry) => String(entry.id) === String(state.currentRoomId));
  return room?.title || "";
}

function textTokenSimilarity(left, right) {
  const leftTokens = new Set(extractMeaningfulTokens(left));
  const rightTokens = new Set(extractMeaningfulTokens(right));
  if (leftTokens.size === 0 || rightTokens.size === 0) {
    return 0;
  }

  let overlap = 0;
  leftTokens.forEach((token) => {
    if (rightTokens.has(token)) {
      overlap += 1;
    }
  });
  return overlap / Math.min(leftTokens.size, rightTokens.size);
}

function extractMeaningfulTokens(text) {
  const stopwords = new Set([
    "그리고", "그러면", "그럼", "대한", "대해", "관련", "설명", "알려줘", "알려", "질문",
    "무엇", "뭐야", "어떻게", "왜", "해줘", "해주세요", "있어", "있는", "없는", "이번",
    "다음", "정리", "예시", "비교", "방법", "차이", "the", "and", "for", "with", "what",
    "how", "why", "about", "please"
  ]);
  return String(text || "")
    .toLowerCase()
    .replace(/[^0-9a-z가-힣]+/g, " ")
    .split(/\s+/)
    .map((token) => token.trim())
    .map(normalizeMeaningfulToken)
    .filter((token) => token.length >= 2 && !stopwords.has(token));
}

function normalizeMeaningfulToken(token) {
  const value = String(token || "").trim();
  if (!/[가-힣]/.test(value)) {
    return value;
  }
  return value
    .replace(/(으로|에서|에게|보다|처럼|까지|부터|이나|거나|하고|이고|이며|인데|인가|은|는|이|가|을|를|에|와|과|의|도|만|로)$/u, "")
    .trim();
}

function cloneConversationPathNodes(pathNodes) {
  return pathNodes.map((node, index) => ({
    ...cloneNode(node),
    parentId: index === 0 ? null : String(pathNodes[index - 1].id),
    depth: index
  }));
}

function buildConversationPathLabel(pathNodes) {
  return pathNodes
    .map((node) => String(node?.title || "").trim())
    .filter(Boolean)
    .join(" -> ");
}

function buildLocalConversationNodes(sourceNodes, includedIds) {
  const sourceNodeMap = new Map(sourceNodes.map((node) => [String(node.id), node]));
  const renderableIds = new Set(
    sourceNodes
      .filter((node) => includedIds.has(String(node.id)))
      .map((node) => String(node.id))
  );

  const resolveParentId = (node) => {
    let cursorId = node.parentId != null ? String(node.parentId) : null;
    while (cursorId) {
      if (renderableIds.has(cursorId)) {
        return cursorId;
      }
      const parentNode = sourceNodeMap.get(cursorId);
      cursorId = parentNode?.parentId != null ? String(parentNode.parentId) : null;
    }
    return null;
  };

  const included = sourceNodes
    .filter((node) => renderableIds.has(String(node.id)))
    .map((node) => ({
      ...cloneNode(node),
      parentId: resolveParentId(node)
    }));

  const childrenByParent = new Map();
  included.forEach((node) => {
    const parentId = node.parentId != null ? String(node.parentId) : null;
    if (!childrenByParent.has(parentId)) {
      childrenByParent.set(parentId, []);
    }
    childrenByParent.get(parentId).push(node);
  });

  childrenByParent.forEach((nodes) => nodes.sort(compareTreeNodeOrder));
  const roots = (childrenByParent.get(null) || []).sort(compareTreeNodeOrder);

  const assignDepth = (node, depth) => {
    node.depth = depth;
    const children = childrenByParent.get(String(node.id)) || [];
    children.forEach((child) => assignDepth(child, depth + 1));
  };

  roots.forEach((root) => assignDepth(root, 0));
  return included.sort(compareTreeNodeOrder);
}

function buildRebuildExtraOptions(pathNodes, sourceNodes = state.nodes) {
  const pathIds = new Set(pathNodes.map((node) => String(node.id)));
  const extraNodes = sourceNodes
    .filter((node) => !pathIds.has(String(node.id)))
    .filter((node) => !isAutoSubtopicSeedNode(node));
  return extraNodes.map((node) => {
    const nodePath = getPathToNode(node.id).filter((entry) => !isAutoSubtopicSeedNode(entry));
    return {
      id: String(node.id),
      parentId: node.parentId != null ? String(node.parentId) : null,
      title: node.title,
      parentTitle: getParentTitleForNode(node),
      pathLabel: buildConversationPathLabel(nodePath),
      depth: node.depth,
      subtreeSize: collectSubtreeIds(node.id, sourceNodes).size
    };
  });
}

function renderRebuildModal() {
  if (!el.rebuildModalBackdrop || !el.rebuildPathPreview || !el.rebuildExtraOptions) {
    return;
  }

  const modalState = state.rebuildModal;
  el.rebuildModalBackdrop.classList.toggle("hidden", !modalState.open);
  if (!modalState.open) {
    return;
  }

  el.rebuildPathPreview.textContent = modalState.pathLabel || "-";
  el.rebuildExtraOptions.innerHTML = "";

  if (!modalState.extraOptions.length) {
    const empty = document.createElement("div");
    empty.className = "rebuild-extra-empty";
    empty.textContent = "이 경로 밖에서 추가로 가져올 노드가 없습니다.";
    el.rebuildExtraOptions.appendChild(empty);
    return;
  }

  const tree = buildTree(modalState.extraOptions);
  const roots = tree.filter((node) => node.parentId === null || !modalState.extraOptions.some((option) => option.id === node.parentId));
  const treeWrap = document.createElement("div");
  treeWrap.className = "rebuild-extra-tree";

  const renderOptionNode = (optionNode) => {
    const label = document.createElement("label");
    label.className = "rebuild-extra-option";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = modalState.selectedExtraBranchIds.has(optionNode.id);
    checkbox.addEventListener("change", (event) => {
      if (event.target.checked) {
        modalState.selectedExtraBranchIds.add(optionNode.id);
      } else {
        modalState.selectedExtraBranchIds.delete(optionNode.id);
      }
    });

    const content = document.createElement("div");
    content.innerHTML = `<div class="rebuild-extra-option-title">${escapeHtml(optionNode.title)}</div><div class="rebuild-extra-option-meta">${escapeHtml(optionNode.pathLabel || optionNode.parentTitle)} / 하위 ${Math.max(0, optionNode.subtreeSize - 1)}개 노드 포함</div>`;
    if (optionNode.subtreeSize <= 1) {
      content.innerHTML = `<div class="rebuild-extra-option-title">${escapeHtml(optionNode.title)}</div><div class="rebuild-extra-option-meta">${escapeHtml(optionNode.pathLabel || optionNode.parentTitle)} / 단일 노드</div>`;
    }

    label.appendChild(checkbox);
    label.appendChild(content);
    if (optionNode.children?.length) {
      const childrenWrap = document.createElement("div");
      childrenWrap.className = "rebuild-extra-children";
      optionNode.children.forEach((child) => childrenWrap.appendChild(renderOptionNode(child)));
      const block = document.createElement("div");
      block.appendChild(label);
      block.appendChild(childrenWrap);
      return block;
    }
    return label;
  };

  roots.forEach((root) => treeWrap.appendChild(renderOptionNode(root)));
  el.rebuildExtraOptions.appendChild(treeWrap);

  // 🚨 모달 용도(mode)에 따라 제목과 완료 버튼 글씨 바꾸기
  const modalTitle = el.rebuildModalBackdrop.querySelector("h3, h2, .modal-title, strong"); // 모달 제목 태그 찾기
  
  if (modalState.mode === "extract") {
    if (modalTitle) modalTitle.textContent = "지식 추출 범위 선택";
    if (el.rebuildModalConfirmBtn) el.rebuildModalConfirmBtn.textContent = "📝 요약 리포트 추출";
  } else {
    if (modalTitle) modalTitle.textContent = "새 대화 재구성";
    if (el.rebuildModalConfirmBtn) el.rebuildModalConfirmBtn.textContent = "새 대화 만들기";
  }

}

function closeRebuildModal() {
  state.rebuildModal = {
    open: false,
    pathLabel: "",
    sourceRoomTitle: "",
    selectedNodeId: null,
    basePathNodeIds: [],
    extraOptions: [],
    selectedExtraBranchIds: new Set()
  };
  render();
}

function loadLocalConversationRoom(roomId) {
  const room = getLocalConversationRoom(roomId);
  if (!room) {
    return false;
  }
  state.currentRoomId = room.id;
  state.nodes = room.nodes.map(cloneNode);
  state.treeNodes = room.treeNodes.map(cloneNode);
  state.selectedNodeId = room.selectedNodeId;
  state.collapsedGraphNodeIds.clear();
  state.graphNodeSizeById.clear();
  state.importantNodeIds.clear();
  state.treeBuildStatus = "completed";
  state.treeProcessingWatcherToken++;
  return true;
}

function onOpenPathModal(mode) { // 🚨 mode 파라미터 받기
  if (isCurrentRoomLocal()) return;
  const selectedNode = state.selectedNodeId ? getNodeById(state.selectedNodeId) : null;
  if (!selectedNode || !state.currentRoomId) return;

  const pathNodes = getPathToNode(selectedNode.id).filter((node) => !isAutoSubtopicSeedNode(node));
  if (!pathNodes.length) return;

  const sourceRoom = state.chatRooms.find((room) => room.id === state.currentRoomId);
  const pathLabel = buildConversationPathLabel(pathNodes);
  
  state.rebuildModal = {
    open: true,
    mode: mode, // 🚨 전달받은 모드(rebuild or extract) 저장!
    pathLabel: pathLabel || selectedNode.title,
    sourceRoomTitle: sourceRoom?.title || "원본 대화",
    selectedNodeId: String(selectedNode.id),
    basePathNodeIds: pathNodes.map((node) => String(node.id)),
    extraOptions: buildRebuildExtraOptions(pathNodes, state.nodes),
    selectedExtraBranchIds: new Set()
  };
  render();
}

// 알림창 띄우는 헬퍼 함수
function showSystemToast(message) {
  const toast = document.getElementById("systemToast");
  const toastText = document.getElementById("systemToastText");
  if (!toast || !toastText) return;

  toastText.textContent = message; // 텍스트만 교체
  toast.classList.remove("hidden");

  setTimeout(() => {
    toast.classList.add("hidden");
  }, 5000);
}

async function confirmRebuildConversation() {
  const modalState = state.rebuildModal;
  if (!modalState.open || !modalState.selectedNodeId || !state.currentRoomId) {
    return;
  }

  // 1. 백엔드로 보낼 공통 데이터(payload) 생성
  const payload = {
    sourceRoomId: Number(state.currentRoomId),
    selectedNodeId: Number(modalState.selectedNodeId),
    extraBranchIds: Array.from(modalState.selectedExtraBranchIds).map(Number)
  };

  // ========================================================
  // 💡 [지식 추출 모드] 지식 추출 API 호출 후 종료
  // ========================================================
  if (modalState.mode === "extract") {
    executeKnowledgeExtraction(payload);
    return;
  }

  // ========================================================
  // 🚀 [대화 재구성 모드] 심화 학습을 위한 새 방 만들기
  // ========================================================
  try {
    const response = await fetch('http://localhost:8080/api/chat/room/rebuild', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${state.currentSession?.accessToken}` 
      },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      throw new Error('대화방 재구성에 실패했습니다.');
    }

    const newRoomId = await response.json(); 
    
    // UI 초기화 및 방 전환 준비
    closeRebuildModal();
    toggleRoomDrawer(false);
    
    // 방 목록 갱신 및 현재 방 ID 설정
    await bootstrapChatRooms(); 
    state.currentRoomId = newRoomId;

    // 2. 새 방의 대화 히스토리 로드
    await loadRoomHistory(newRoomId);

    // 3. 로딩 직후 마지막 노드(심화 학습 기준점)로 포커스 이동
    if (state.nodes && state.nodes.length > 0) {
      const lastNode = state.nodes[state.nodes.length - 1];
      state.selectedNodeId = String(lastNode.id);
      console.log("심화 학습 포커스 이동 완료! Node ID:", state.selectedNodeId);
    }

    // 4. 화면 렌더링
    render(); 

    // 5. 🌟 [핵심 업데이트] 트리 노드 대신 세련된 토스트 알림 띄우기
    showSystemToast("대화가 재구성 되었습니다. 재구성된 대화를 기반으로 답변이 생성됩니다.");

  } catch (error) {
    console.error("재구성 API 호출 에러:", error);
    alert(toUiError(error));
  }
}


/**
 * 💡 지식 추출 API 호출 및 다중 경로 미니 그래프 표시 함수
 */
/**
 * 💡 지식 추출 API 호출 및 다중 경로 미니 그래프 표시 함수
 */
async function executeKnowledgeExtraction(payload) {
  const contentArea = document.getElementById("extractResultContent");
  const pathPreviewArea = document.getElementById("extractResultPathPreview"); 
  const modalBackdrop = document.getElementById("extractResultModalBackdrop");

  contentArea.textContent = "AI가 리포트를 생성 중입니다... ⏳";
  pathPreviewArea.innerHTML = ""; 
  modalBackdrop.classList.remove("hidden");
  
  closeRebuildModal();

  // 2. 수집할 노드 ID들을 담을 Set
  const targetIds = new Set();

  // 🌟 (핵심 헬퍼 함수) 특정 노드에서 루트까지 거슬러 올라가며 ID를 수집합니다.
  const addPathToRoot = (nodeId) => {
    let cursor = state.nodes.find(n => String(n.id) === String(nodeId));
    while (cursor) {
      targetIds.add(String(cursor.id));
      cursor = cursor.parentId ? state.nodes.find(n => String(n.id) === String(cursor.parentId)) : null;
    }
  };

  // (1) 사용자가 선택한 기본 기준 노드에서 루트까지 수집
  addPathToRoot(payload.selectedNodeId);

  // (2) '추가로 가져올 노드/가지' 로 선택한 ID 수집
  if (payload.extraBranchIds && payload.extraBranchIds.length > 0) {
    payload.extraBranchIds.forEach(branchId => {
      // 1) 선택한 가지의 하위 노드(자식들) 전부 수집
      const subIds = collectSubtreeIds(branchId, state.nodes);
      subIds.forEach(id => targetIds.add(String(id)));
      
      // 2) 🌟 빼먹기 쉬운 윗단(부모들)도 루트까지 거슬러 올라가며 꽉꽉 채워 넣기!
      addPathToRoot(branchId);
    });
  }

  // 3. 빠진 것 없이 완벽하게 수집된 ID들로 트리 재조립
  const nodesToDraw = buildLocalConversationNodes(state.nodes, targetIds);

  // 4. 모달창 미니 그래프 그리기 (drawMiniGraph 함수는 기존 그대로 유지)
  drawMiniGraph(pathPreviewArea, nodesToDraw);

  try {
    // 5. 백엔드 통신
    const response = await fetch('http://localhost:8080/api/chat/room/extract', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${state.currentSession?.accessToken}`
      },
      body: JSON.stringify(payload)
    });

    if (!response.ok) throw new Error(`요청 실패 (상태: ${response.status})`);
    const resultText = await response.text();
    contentArea.innerHTML = resultText.replace(/\n/g, "<br>");
    // 🌟 핵심: 결과 텍스트를 마크다운으로 렌더링
    contentArea.innerHTML = marked.parse(resultText);

  } catch (error) {
    console.error("지식 추출 에러:", error);
    contentArea.textContent = "❌ 오류가 발생했습니다: " + error.message;
  }
}

/**
 * 🌟 [새로 추가] 모달창 안에 쏙 들어가는 전용 미니 트리 렌더링 함수
 * (이 함수를 executeKnowledgeExtraction 바로 아래에 붙여넣어 주세요)
 */
function drawMiniGraph(container, nodesToDraw) {
  container.innerHTML = "";
  if (!nodesToDraw || nodesToDraw.length === 0) return;

  // 민교님이 만들어둔 기존 그래프 레이아웃 엔진 완벽 재활용!
  const graph = getTreeGraphLayout(nodesToDraw);
  const svgNS = "http://www.w3.org/2000/svg";

  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("class", "tree-graph-svg");
  // 트리가 가운데 예쁘게 맞도록 viewBox 설정
  svg.setAttribute("viewBox", `0 0 ${graph.width} ${graph.height}`);
  svg.setAttribute("preserveAspectRatio", "xMidYMin meet");
  svg.style.width = "100%";
  svg.style.height = "100%";
  svg.style.minHeight = "250px"; // 모달창에서 찌그러지지 않게 최소 높이 보장

  // 선(Link) 그리기
  graph.links.forEach((link) => {
    const line = document.createElementNS(svgNS, "line");
    line.setAttribute("x1", String(link.x1));
    line.setAttribute("y1", String(link.y1));
    line.setAttribute("x2", String(link.x2));
    line.setAttribute("y2", String(link.y2));
    line.setAttribute("class", "tree-link");
    svg.appendChild(line);
  });

  // 동그라미 노드 그리기
  graph.nodes.forEach((node) => {
    const nodeGroup = document.createElementNS(svgNS, "g");
    nodeGroup.setAttribute("class", "tree-node-group");

    const circle = document.createElementNS(svgNS, "circle");
    circle.setAttribute("cx", String(node.x));
    circle.setAttribute("cy", String(node.y));
    circle.setAttribute("r", "20");
    // 💡 포인트: 추출된 경로는 강조되어야 하니 모두 'active' 색상(민트색)을 입혀줍니다!
    circle.setAttribute("class", "tree-node-circle active"); 

    const label = document.createElementNS(svgNS, "text");
    label.setAttribute("x", String(node.x));
    label.setAttribute("y", String(node.y + 5));
    label.setAttribute("class", "tree-node-label");
    label.textContent = node.title.length > 6 ? `${node.title.slice(0, 6)}...` : node.title;
    label.style.pointerEvents = "none";

    nodeGroup.appendChild(circle);
    nodeGroup.appendChild(label);
    svg.appendChild(nodeGroup);
  });

  container.appendChild(svg);
}

/**
 * 💡 [추가] 모달창 닫기 및 복사 버튼 이벤트 바인딩
 */
document.addEventListener('DOMContentLoaded', () => {
  const extractResultCloseBtn = document.getElementById("extractResultCloseBtn");
  const extractResultConfirmBtn = document.getElementById("extractResultConfirmBtn");
  const extractResultCopyBtn = document.getElementById("extractResultCopyBtn");
  const extractResultModalBackdrop = document.getElementById("extractResultModalBackdrop");

  // 닫기/확인 버튼 동작
  const closeExtractModal = () => {
    extractResultModalBackdrop.classList.add("hidden");
  };
  
  if (extractResultCloseBtn) extractResultCloseBtn.addEventListener("click", closeExtractModal);
  if (extractResultConfirmBtn) extractResultConfirmBtn.addEventListener("click", closeExtractModal);

  // 복사 버튼 동작
  if (extractResultCopyBtn) {
    extractResultCopyBtn.addEventListener("click", async () => {
      const content = document.getElementById("extractResultContent").textContent;
      try {
        await navigator.clipboard.writeText(content);
        alert("리포트가 클립보드에 복사되었습니다!");
      } catch (err) {
        alert("복사 실패: " + err);
      }
    });
  }
});

// 2. 결과창 모달 띄우기 & 닫기 로직
function showExtractResultModal(summaryText) {
  const modal = document.getElementById("extractResultModalBackdrop");
  const content = document.getElementById("extractResultContent");
  
  if (modal && content) {
    content.textContent = summaryText; // 백엔드에서 받은 AI 요약본 넣기
    modal.classList.remove("hidden");
  }
}

function closeExtractResultModal() {
  document.getElementById("extractResultModalBackdrop")?.classList.add("hidden");
}

// 3. 결과창 버튼 이벤트 연결 (기존 bindEvents() 함수 안에 넣거나, 파일 맨 아래에서 실행되도록 추가)
document.getElementById("extractResultCloseBtn")?.addEventListener("click", closeExtractResultModal);
document.getElementById("extractResultConfirmBtn")?.addEventListener("click", closeExtractResultModal);

document.getElementById("extractResultCopyBtn")?.addEventListener("click", async () => {
  const content = document.getElementById("extractResultContent")?.textContent;
  if (!content) return;
  
  try {
    await navigator.clipboard.writeText(content);
    alert("요약 리포트가 클립보드에 복사되었습니다!");
  } catch (err) {
    alert("복사에 실패했습니다.");
  }
});

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

  applyInsightDepthUi(depth, fallbackNode);
  renderConversationSummary(insight?.conversationSummary);
}

function applyInsightDepthUi(depthValue, node = null) {
  const notice = getActiveRouteNotice(node);
  const focusMetrics = getRouteFocusMetrics(node);
  const zeroDepthTopicDriftNotice = buildZeroDepthTopicDriftNotice(node, focusMetrics);
  const ratio = Math.min(100, Math.max(10, Math.round((focusMetrics.score / 3) * 100)));
  const isDepthOverLimit = focusMetrics.shouldNotify;
  const depthNotice = notice && (isDepthRouteNoticeKind(notice.kind) || notice.kind === "topic")
    ? notice
    : zeroDepthTopicDriftNotice;

  if (el.depthBar) {
    el.depthBar.style.width = `${ratio}%`;
    el.depthBar.style.background = isDepthOverLimit
      ? "linear-gradient(90deg, var(--warning), var(--danger))"
      : "var(--control-active-bg)";
  }

  if (depthNotice) {
    if (depthNotice.type === "strong" && el.depthBar) {
      el.depthBar.style.background = "linear-gradient(90deg, var(--warning), var(--danger))";
    }
    if (el.driftAlert) {
      el.driftAlert.textContent = depthNotice.message;
      el.driftAlert.className = `alert route-notice ${depthNotice.type === "strong" ? "strong" : "info"} ${depthNotice.kind || ""}`.trim();
    }
    return;
  }

  if (isDepthOverLimit) {
    if (el.driftAlert) {
      const noticeKind = focusMetrics.isDeepening && focusMetrics.isLeafBiased
        ? "depth-mixed"
        : (focusMetrics.isDeepening ? "deepening" : "leaf-bias");
      el.driftAlert.textContent = buildFocusDepthStatusMessage(focusMetrics);
      el.driftAlert.className = `alert route-notice info ${noticeKind}`;
    }
    return;
  }

  if (el.driftAlert) {
    el.driftAlert.textContent = focusMetrics.focusDepth > 0
      ? `연속 심화 ${focusMetrics.focusDepth}단계: 정상 범위 안에서 유지되고 있습니다.`
      : "연속 심화 0단계: 학습 경로가 균형 있게 유지되고 있습니다.";
    el.driftAlert.className = "alert";
  }
}

function buildZeroDepthTopicDriftNotice(node, focusMetrics) {
  if (!node || !focusMetrics || Number(focusMetrics.focusDepth || 0) !== 0) {
    return null;
  }
  if (!node.parentId || Number(node.depth || 0) <= 0) {
    return null;
  }
  if (hasAcceptedRootTopicNotice(node.id)) {
    return null;
  }

  const rootTopic = getRootTopicLabelForNode(node.id);
  const notice = {
    nodeId: String(node.id),
    type: "strong",
    kind: "topic",
    message: `연속 심화 0단계입니다. 대주제 '${rootTopic}'와 흐름이 끊긴 노드일 수 있어 새 대화방에서 분리하는 것을 권장합니다.`,
    createdAt: Date.now()
  };
  setRouteNotice(notice);
  clearBranchNotice();
  if (state.rootTopicNoticeNodeIds.has(String(node.id))) {
    return notice;
  }
  state.rootTopicNoticeNodeIds.add(String(node.id));
  setTimeout(() => {
    void openRootTopicDecisionDialog({
      unrelated: true,
      rootTopic,
      similarity: 0
    }).then(async (choice) => {
      if (choice === "continue") {
        rememberAcceptedRootTopicNotice(node.id);
        clearRouteNotice();
        renderInsights();
        return;
      }
      if (choice === "cancel") {
        return;
      }
      if (choice !== "new_room") {
        return;
      }
      try {
        const currentNode = getNodeById(node.id);
        const question = currentNode?.userQuestion || node.userQuestion || node.title || "";
        const localRoom = getLocalConversationRoom();
        const sourceRoomId = localRoom?.sourceRoomId || state.currentRoomId;
        await startNewRoomWithQuestion(question, { removeNodeId: node.id, sourceRoomId });
      } catch (error) {
        setAuthMessage(`새 대화방 생성 실패: ${toUiError(error)}`, "error");
        render();
      }
    });
  }, 0);
  return notice;
}

function buildFocusDepthStatusMessage(metrics) {
  const topic = metrics.branchAnchorTitle || "현재 주제";
  if (metrics.isDeepening && metrics.isLeafBiased) {
    return `[심화] '${topic}'에서 ${metrics.focusDepth}단계째 이어지고 있습니다.\n[리프 편향] 다른 리프보다 ${metrics.depthLead}단계 깊습니다. 다른 하위 노드도 함께 확인해보세요.`;
  }
  if (metrics.isDeepening) {
    return `[심화 알림] '${topic}'에서 ${metrics.focusDepth}단계째 이어지고 있습니다. 다른 하위 노드도 함께 확인해보세요.`;
  }
  return `[리프 편향] 현재 경로가 다른 리프보다 ${metrics.depthLead}단계 깊습니다. 다른 하위 노드도 함께 확인해보세요.`;
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
    min: 0,
    collapseAt: 72,
    panelKey: "tree",
    direction: "right"
  });

  setupSingleResizer({
    handle: el.insightResizeHandle,
    panel: el.insightPanel,
    cssVar: "--insight-panel-width",
    min: 0,
    collapseAt: 72,
    panelKey: "insight",
    direction: "left"
  });
}

function setupSingleResizer({ handle, panel, cssVar, min, collapseAt, panelKey, direction }) {
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
    const nextWidth = clamp(startWidth + signedDelta, min, getPanelResizeMaxWidth());
    if (nextWidth <= collapseAt) {
      setPanelCollapsed(panelKey, true);
      document.documentElement.style.setProperty(cssVar, "0px");
      dragging = false;
      handle.classList.remove("dragging");
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      return;
    }
    document.documentElement.style.setProperty(cssVar, `${nextWidth}px`);
  };

  const onUp = () => {
    dragging = false;
    handle.classList.remove("dragging");
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
  };

  handle.addEventListener("pointerdown", (event) => {
    if (
      (cssVar === "--tree-panel-width" && state.treePanelCollapsed)
      || (cssVar === "--insight-panel-width" && state.insightPanelCollapsed)
    ) {
      return;
    }
    dragging = true;
    startX = event.clientX;
    startWidth = panel.getBoundingClientRect().width;
    handle.classList.add("dragging");
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });
}

function getPanelResizeMaxWidth() {
  const appWidth = document.getElementById("appViewInner")?.getBoundingClientRect().width || window.innerWidth || 1200;
  return Math.max(260, Math.floor(appWidth - 24));
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
  // 🌟 핵심: AI 답변(마크다운)을 HTML로 변환
  let displayContent = text;
  if (role === "ai") {
    displayContent = marked.parse(text); // 마크다운 -> HTML 변환
  } else {
    displayContent = escapeHtml(text); // 유저 질문은 보안을 위해 일반 텍스트 처리
  }

  // 뒤에 붙어있던 <span class="time"> 태그 영역을 완전히 날려버립니다.
bubble.innerHTML = `${displayContent}`;
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

function getClosestGraphZoomPreset(value) {
  const zoom = Number(value) || 1;
  return GRAPH_ZOOM_PRESETS.reduce((closest, preset) => (
    Math.abs(preset - zoom) < Math.abs(closest - zoom) ? preset : closest
  ), GRAPH_ZOOM_PRESETS[0]);
}

function changeGraphZoom(direction) {
  if (state.treeViewMode !== "graph") {
    return;
  }
  state.graphScopeMode = "range";
  const current = getClosestGraphZoomPreset(state.graphZoom);
  const currentIndex = GRAPH_ZOOM_PRESETS.indexOf(current);
  const nextIndex = clamp(currentIndex + Math.sign(direction), 0, GRAPH_ZOOM_PRESETS.length - 1);
  state.graphZoom = GRAPH_ZOOM_PRESETS[nextIndex];
  renderTree();
}

function setGraphZoom(value) {
  if (state.treeViewMode !== "graph") {
    return;
  }
  if (value === "path") {
    state.graphScopeMode = "path";
  } else {
    state.graphScopeMode = "range";
    state.graphZoom = getClosestGraphZoomPreset(value);
  }
  renderTree();
}

function resetGraphZoom() {
  state.graphZoom = 1;
  state.graphScopeMode = "range";
  if (state.treeFocusMode) {
    state.focusMindmapViewport = null;
  } else {
    state.graphTreeScale = 1;
  }
  renderTree();
}

function changeGraphTreeScale(delta) {
  if (state.treeViewMode !== "graph") {
    return;
  }
  if (state.treeFocusMode && focusCytoscape) {
    const currentZoom = focusCytoscape.zoom();
    const nextZoom = clamp(currentZoom + delta, focusCytoscape.minZoom(), focusCytoscape.maxZoom());
    focusCytoscape.zoom({
      level: nextZoom,
      renderedPosition: {
        x: (el.treeRoot?.clientWidth || 0) / 2,
        y: (el.treeRoot?.clientHeight || 0) / 2
      }
    });
    state.focusMindmapViewport = {
      zoom: focusCytoscape.zoom(),
      pan: focusCytoscape.pan()
    };
    return;
  }
  state.graphTreeScale = clamp(Number((state.graphTreeScale + delta).toFixed(2)), 0.5, 4);
  renderTree();
}

function setGraphNodeShape(shape) {
  if (state.treeViewMode !== "graph") {
    return;
  }
  if (shape !== "circle" && shape !== "box") {
    return;
  }
  state.graphNodeShape = shape;
  renderTree();
}

function setFocusGraphLayout(layout) {
  if (state.treeViewMode !== "graph" || !state.treeFocusMode) {
    return;
  }
  if (layout !== "mindmap" && layout !== "tree") {
    return;
  }
  state.focusGraphLayout = layout;
  renderTree();
}

function toggleGraphResizeMode() {
  if (state.treeViewMode !== "graph") {
    return;
  }
  state.graphResizeMode = !state.graphResizeMode;
  renderTree();
}

function toggleTreeFocusMode() {
  setTreeFocusMode(!state.treeFocusMode);
}

function setTreeFocusMode(enabled) {
  const wasFocusMode = state.treeFocusMode;
  state.treeFocusMode = Boolean(enabled);
  if (state.treeFocusMode && state.treeViewMode !== "graph") {
    state.treeViewMode = "graph";
  }
  if (state.treeFocusMode) {
    state.graphResizeMode = false;
    state.focusGraphLayout = "mindmap";
    if (!wasFocusMode) {
      state.focusMindmapViewport = null;
    }
  }
  document.body.classList.toggle("tree-focus-active", state.currentView === "app" && state.treeFocusMode);
  render();
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
  return state.nodes.find((node) => String(node.id) === String(id)) || null;
}

function getPathToNode(nodeId) {
  const nodeMap = new Map(state.nodes.map((node) => [String(node.id), node]));
  const path = [];
  let cursor = nodeMap.get(String(nodeId));

  while (cursor) {
    path.unshift(cursor);
    cursor = cursor.parentId ? nodeMap.get(String(cursor.parentId)) : null;
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

function getGraphRenderOptions() {
  const scale = clamp(Number(state.graphNodeSizeScale) || 1, 0.8, 1.5);
  const isBox = state.graphNodeShape === "box";
  const zoom = clamp(Number(state.graphZoom) || 1, 0.4, 2.8);
  const semanticCompact = zoom < 1;
  const labeledDepth = zoom <= 0.45 ? -1 : (zoom <= 0.55 ? 0 : (zoom <= 0.65 ? 1 : (zoom <= 0.75 ? 2 : (zoom <= 0.85 ? 3 : (zoom <= 0.9 ? 4 : Infinity)))));
  const dotProgress = clamp((zoom - 0.4) / 0.6, 0, 1);
  return {
    shape: isBox ? "box" : "circle",
    scale,
    zoom,
    semanticCompact,
    labeledDepth,
    prominentDepth: semanticCompact && labeledDepth >= 1 ? 1 : -1,
    compactDotRadius: Math.round(8 + dotProgress * 3),
    radius: Math.round((semanticCompact ? 24 : 36) * scale),
    circleLabelLength: Math.max(7, Math.floor(9 * scale)),
    minBoxWidth: Math.round((state.treeFocusMode ? 188 : 158) * scale),
    maxCharsPerLine: Math.max(12, Math.floor((state.treeFocusMode ? 24 : 20) * scale)),
    horizontalPadding: Math.round((isBox ? (state.treeFocusMode ? 34 : 30) : 18) * scale),
    verticalPadding: Math.round((isBox ? 10 : 12) * scale),
    lineHeight: Math.round((isBox ? 17 : 17) * scale),
    fontSize: Math.round((isBox ? 16 : 13) * scale),
    cornerRadius: Math.round(12 * scale)
  };
}

function getGraphNodeSizeKey(nodeId, shape = state.graphNodeShape) {
  return `${shape}:${String(nodeId)}`;
}

function getGraphNodeSize(nodeId) {
  const stored = state.graphNodeSizeById.get(getGraphNodeSizeKey(nodeId));
  if (typeof stored === "number") {
    const scale = clamp(stored, 0.65, 2.4);
    return { width: scale, height: scale };
  }
  return {
    width: clamp(Number(stored?.width) || 1, 0.65, 7),
    height: clamp(Number(stored?.height) || 1, 0.65, 4)
  };
}

function getCircleNodeTitle(title, maxLength) {
  const clean = String(title || "제목 없음").trim();
  
  // 💡 원이 커졌으므로 4에서 5 또는 6으로 키우면 글자가 더 많이 보입니다!
  const safeLength = Math.min(maxLength, 6); 
  
  return clean.length > safeLength ? `${clean.slice(0, safeLength)}..` : clean;
}

function getGraphDisplayTitle(node) {
  const title = String(node?.title || "제목 없음").trim();
  if (node?.parentId != null) {
    return title;
  }
  return extractRootTopicTitle(title) || title;
}

function extractRootTopicTitle(title) {
  const clean = String(title || "").replace(/\s+/g, " ").trim();
  if (!clean) {
    return "";
  }

  const explicitMatch = clean.match(/^대주제(?:는|:)?\s*(.+?)(?:이고|이며|입니다|,|\.|$)/);
  if (explicitMatch?.[1]) {
    return explicitMatch[1].trim();
  }

  const subtopicMatch = clean.match(/^(.+?)(?:이고|이며|,)?\s*소주제(?:는|:)/);
  if (subtopicMatch?.[1]) {
    return subtopicMatch[1].trim();
  }

  return clean;
}

function wrapGraphTitle(title, maxCharsPerLine) {
  const clean = String(title || "제목 없음").replace(/\s+/g, " ").trim();
  if (clean.length <= maxCharsPerLine) {
    return [clean];
  }

  const lines = [];
  let current = "";
  clean.split(" ").forEach((word) => {
    if (!current) {
      current = word;
      return;
    }
    if ((current + word).length + 1 <= maxCharsPerLine) {
      current += ` ${word}`;
    } else {
      lines.push(current);
      current = word;
    }
  });
  if (current) {
    lines.push(current);
  }

  const normalized = [];
  lines.forEach((line) => {
    if (line.length <= maxCharsPerLine) {
      normalized.push(line);
      return;
    }
    for (let index = 0; index < line.length; index += maxCharsPerLine) {
      normalized.push(line.slice(index, index + maxCharsPerLine));
    }
  });
  return normalized;
}

function getGraphNodeVisualMode(node, options) {
  const depth = Number(node?.depth) || 0;
  if (options.semanticCompact && depth > options.labeledDepth) {
    return "dot";
  }
  if (options.semanticCompact && depth <= options.prominentDepth) {
    return "prominent";
  }
  return "normal";
}

function measureGraphNode(node, title, options) {
  const nodeId = node.id;
  const visualMode = getGraphNodeVisualMode(node, options);
  if (visualMode === "dot") {
    const radius = Math.max(5, options.compactDotRadius);
    const visualShape = options.shape === "box" ? "mini-box" : "dot";
    return {
      width: visualShape === "mini-box" ? radius * 2.3 : radius * 2,
      height: visualShape === "mini-box" ? radius * 1.7 : radius * 2,
      rx: radius,
      ry: radius,
      radius,
      fontSize: options.fontSize,
      lineHeight: options.lineHeight,
      visualShape,
      showLabel: false,
      lines: []
    };
  }

  const prominenceScale = visualMode === "prominent" ? 1.04 : 1;
  const nodeSize = getGraphNodeSize(nodeId);
  const textScale = visualMode === "prominent" ? 1.04 : 1;
  if (options.shape === "circle") {
    const rx = Math.round(options.radius * nodeSize.width * prominenceScale);
    const ry = Math.round(options.radius * nodeSize.height * prominenceScale);
    const circleLabelLength = Math.max(
      options.circleLabelLength,
      Math.floor((rx * 2 - 8) / Math.max(6, options.fontSize * 0.38))
    );
    return {
      width: rx * 2,
      height: ry * 2,
      rx,
      ry,
      radius: Math.max(rx, ry),
      fontSize: Math.round(options.fontSize * textScale),
      lineHeight: options.lineHeight,
      visualShape: "circle",
      showLabel: true,
      lines: [getCircleNodeTitle(title, circleLabelLength)]
    };
  }

  const lineCapacity = Math.max(options.maxCharsPerLine, Math.floor(options.maxCharsPerLine * nodeSize.width * 1.35));
  const lines = wrapGraphTitle(title, lineCapacity);
  const longestLine = Math.max(...lines.map((line) => line.length), 1);
  const fontSize = Math.round(options.fontSize * textScale);
  const baseWidth = Math.max(options.minBoxWidth, Math.round(longestLine * options.fontSize * 0.72) + options.horizontalPadding * 2);
  const baseHeight = lines.length * options.lineHeight + options.verticalPadding * 2;
  return {
    width: Math.round(baseWidth * nodeSize.width * prominenceScale),
    height: Math.round(baseHeight * nodeSize.height * prominenceScale),
    rx: 0,
    ry: 0,
    radius: 0,
    fontSize: Math.round(fontSize * prominenceScale),
    lineHeight: Math.round(options.lineHeight * nodeSize.height * prominenceScale),
    visualShape: "box",
    showLabel: true,
    lines
  };
}

function getGraphLabelStartY(node, options) {
  if (node.visualShape !== "box") {
    return node.y + Math.round(options.fontSize * 0.36);
  }
  const lineHeight = node.lineHeight || options.lineHeight;
  return node.y - ((node.lines.length - 1) * lineHeight) / 2 + Math.round((node.fontSize || options.fontSize) * 0.36);
}

function getTreeGraphLayout(nodes, options = getGraphRenderOptions(), hiddenCountMap = new Map()) {
  const tree = buildTree(nodes);
  const roots = tree.filter((node) => node.parentId === null);
  const xGap = Math.round((state.treeFocusMode ? 38 : (options.semanticCompact ? 46 : (options.shape === "box" ? 18 : 44))) * options.scale);
  const yGap = Math.round((state.treeFocusMode ? 112 : (options.semanticCompact ? 78 : (options.shape === "box" ? 91 : 98))) * options.scale);
  const margin = Math.round((state.treeFocusMode ? 54 : (options.shape === "box" ? 24 : 34)) * options.scale);
  const placed = [];

  function measureSubtree(node, depth) {
    const displayTitle = getGraphDisplayTitle(node);
    const metrics = measureGraphNode(node, displayTitle, options);
    const childLayouts = node.children.map((child) => measureSubtree(child, depth + 1));
    const childrenWidth = childLayouts.reduce((sum, child) => sum + child.subtreeWidth, 0)
      + Math.max(0, childLayouts.length - 1) * xGap;
    return {
      ...node,
      title: displayTitle,
      hiddenCount: hiddenCountMap.get(String(node.id)) || 0,
      ...metrics,
      depth,
      childLayouts,
      subtreeWidth: Math.max(metrics.width, childrenWidth)
    };
  }

  function placeSubtree(layout, left) {
    const x = left + layout.subtreeWidth / 2;
    const y = margin + layout.depth * yGap;
    placed.push({
      id: layout.id,
      title: layout.title,
      depth: layout.depth,
      x,
      y,
      width: layout.width,
      height: layout.height,
      rx: layout.rx,
      ry: layout.ry,
      radius: layout.radius,
      fontSize: layout.fontSize,
      lineHeight: layout.lineHeight,
      visualShape: layout.visualShape,
      showLabel: layout.showLabel,
      lines: layout.lines,
      hiddenCount: layout.hiddenCount
    });

    const childrenWidth = layout.childLayouts.reduce((sum, child) => sum + child.subtreeWidth, 0)
      + Math.max(0, layout.childLayouts.length - 1) * xGap;
    let childLeft = left + (layout.subtreeWidth - childrenWidth) / 2;
    layout.childLayouts.forEach((child) => {
      placeSubtree(child, childLeft);
      childLeft += child.subtreeWidth + xGap;
    });
  }

  let cursorX = margin;
  roots.map((root) => measureSubtree(root, 0)).forEach((rootLayout) => {
    placeSubtree(rootLayout, cursorX);
    cursorX += rootLayout.subtreeWidth + xGap;
  });

  const maxDepth = Math.max(...placed.map((p) => p.depth), 0);
  const graphNodes = placed;

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
          y1: parent.y + parent.height / 2,
          x2: target.x,
          y2: target.y - target.height / 2
        });
      }
    });
  });

  const maxX = Math.max(...graphNodes.map((p) => p.x + p.width / 2), margin);
  const minX = Math.min(...graphNodes.map((p) => p.x - p.width / 2), margin);
  const maxY = Math.max(...graphNodes.map((p) => p.y + p.height / 2), margin);
  const minY = Math.min(...graphNodes.map((p) => p.y - p.height / 2), margin);
  const contentWidth = maxX - minX;
  const minCanvasWidth = 320;
  const width = Math.max(minCanvasWidth, contentWidth + margin * 2);
  const shiftX = margin - minX + (width - (contentWidth + margin * 2)) / 2;
  const shiftY = margin - minY;

  graphNodes.forEach((node) => {
    node.x += shiftX;
    node.y += shiftY;
  });
  links.forEach((link) => {
    link.x1 += shiftX;
    link.x2 += shiftX;
    link.y1 += shiftY;
    link.y2 += shiftY;
  });

  return {
    nodes: graphNodes,
    links,
    width,
    height: Math.max(220, maxY - minY + margin * 2, margin * 2 + maxDepth * yGap + 36)
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
    submitButton.disabled = !canChat || state.isSendingMessage;
    
    // 🌟 핵심: 텍스트 대신 '전송(화살표)'과 '처리중(스피너)' SVG 아이콘을 상황에 맞게 렌더링
    if (state.isSendingMessage) {
      // 로딩 중: 빙글빙글 도는 스피너 아이콘
      submitButton.innerHTML = `<svg class="animate-spin" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" width="20" height="20"><path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path></svg>`;
    } else {
      // 평상시: 세련된 오른쪽 화살표 아이콘
      submitButton.innerHTML = `<svg fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24" width="20" height="20"><path stroke-linecap="round" stroke-linejoin="round" d="M4.5 10.5L12 3m0 0l7.5 7.5M12 3v18"></path></svg>`;
    }
  }

  el.chatInput.placeholder = canChat
    ? (state.isSendingMessage ? "요청을 처리하는 중입니다..." : "질문을 입력하면 현재 선택 노드에서 분기됩니다.")
    : "로그인 후 질문을 입력할 수 있습니다.";
}

function setChatSubmitBusy(isBusy, label = "") {
  state.isSendingMessage = Boolean(isBusy);
  state.chatSubmitStatusLabel = isBusy ? String(label || "처리 중...") : "";
  syncChatInputAvailability();
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

// PDF 추출 버튼 이벤트 리스너
document.addEventListener("DOMContentLoaded", () => {
  const pdfBtn = document.getElementById("pdf-btn");

  if (pdfBtn) {
    pdfBtn.addEventListener("click", async () => {
      // 1. 버튼 상태를 로딩 중으로 변경
      const originalText = pdfBtn.textContent;
      pdfBtn.textContent = "⏳ PDF 생성 중...";
      pdfBtn.disabled = true;

      try {
        // 2. 화면에 보이는 모달창에서 데이터 가져오기
        // 🚨 핵심 수정: innerText가 아니라 innerHTML을 사용하여 트리 구조 태그를 그대로 가져옴!
        const modalContent = document.getElementById("extractResultContent").innerHTML;
        const modalPath = document.getElementById("extractResultPathPreview").innerHTML;

        // 💡 [추가] 오늘 날짜 자동 입력
        const today = new Date();
        document.getElementById("pdf-date").innerText = `${today.getFullYear()}년 ${today.getMonth() + 1}월 ${today.getDate()}일`;

        // 3. 흰색 도화지에 데이터 옮겨 적기 (가독성을 위한 최적의 여백 리팩토링)
        const pdfBox = document.getElementById("pdf-content-box");
        
        // 상위 엘리먼트의 악성 pre-wrap 속성을 완벽히 해제
        pdfBox.style.whiteSpace = "normal"; 

        pdfBox.innerHTML = `
          <style>
            /* 빈 태그 제거 */
            .pdf-markdown-content p:empty, 
            .pdf-markdown-content br { 
              display: none !important; 
            }

            /* 본문 텍스트 단락 간 가독성을 위한 최적의 하단 마진 설정 */
            .pdf-markdown-content p {
              margin-top: 0px !important;
              margin-bottom: 12px !important;
              line-height: 1.6;
              color: #334155 !important;
            }

            /* 본문 내부의 소제목(1. 데이터베이스 개요 등) 위아래 적정 여백 */
            .pdf-markdown-content h1, 
            .pdf-markdown-content h2, 
            .pdf-markdown-content h3 {
              margin-top: 20px !important;   /* 이전 문단과 확실히 구분되도록 위쪽 여백 부여 */
              margin-bottom: 6px !important;  /* 제목 바로 밑 본문 내용과 자연스럽게 밀착 */
              font-size: 16px !important;
              font-weight: 700 !important;
              color: #0f172a !important;
            }

            /* 리스트 문단(점 표기 방식 등) 간격 최적화 */
            .pdf-markdown-content ul, 
            .pdf-markdown-content ol {
              margin-top: 0px !important;
              margin-bottom: 12px !important;
              padding-left: 20px !important;
            }
            .pdf-markdown-content li {
              margin-bottom: 4px !important;
              line-height: 1.5;
              color: #334155 !important;
            }

            /* 리포트 전체의 맨 첫 번째 요소는 대제목 바로 밑에 붙도록 상단 마진 완전 제거 */
            .pdf-markdown-content > *:first-child {
              margin-top: 0px !important;
            }
          </style>

          <h3 style="color: #121212; font-size: 18px; font-weight: 800; margin: 0 0 10px 0; padding: 0; display: flex; align-items: center; gap: 8px;">
            <span style="color: #43dab8;">📝</span> AI 학습 요약 리포트
          </h3>
          
          <div class="pdf-markdown-content" style="color: #334155; text-align: justify; word-break: keep-all; margin-bottom: 30px;">
            ${modalContent}
          </div>
          
          <h3 style="color: #121212; font-size: 18px; font-weight: 800; margin: 32px 0 10px 0; padding: 0; display: flex; align-items: center; gap: 8px;">
            <span style="color: #43dab8;">📍</span> 선택된 지식 경로
          </h3>
          
          <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; display: flex; justify-content: center; align-items: center; padding: 35px 20px; margin-bottom: 10px; box-shadow: inset 0 2px 4px rgba(0,0,0,0.02); overflow: hidden;">
            <div style="transform: scale(1.05); transform-origin: center; white-space: normal !important;">
              ${modalPath}
            </div>
          </div>
        `;

        // 4. 흰색 도화지를 html2canvas로 사진 찍기 (스케일 2로 고화질)
        const reportElement = document.getElementById("report-area");
        
        // 렌더링을 위해 잠깐 화면 안으로 가져왔다가 바로 숨김 처리
        reportElement.style.left = "0px";
        reportElement.style.zIndex = "-1"; // 뒤로 숨김
        
        const canvas = await window.html2canvas(reportElement, {
          scale: 2,
          useCORS: true,
          backgroundColor: '#ffffff'
        });
        
        // 다시 화면 밖으로 치우기
        reportElement.style.left = "-9999px";

        const imgData = canvas.toDataURL('image/png');

        // 5. 찍은 사진을 jsPDF를 이용해 A4 용지에 맞게 분할하여 PDF로 만들기
        const { jsPDF } = window.jspdf;
        const doc = new jsPDF('p', 'mm', 'a4');
        
        const imgWidth = 210; // A4 가로
        const pageHeight = 295; // A4 세로
        const imgHeight = (canvas.height * imgWidth) / canvas.width;
        let heightLeft = imgHeight;
        let position = 0;

        // 첫 페이지 붙이기
        doc.addImage(imgData, 'PNG', 0, position, imgWidth, imgHeight);
        heightLeft -= pageHeight;

        // 내용이 길면 새 페이지 추가해서 이어 붙이기
        while (heightLeft >= 0) {
          position = heightLeft - imgHeight;
          doc.addPage();
          doc.addImage(imgData, 'PNG', 0, position, imgWidth, imgHeight);
          heightLeft -= pageHeight;
        }

        // 6. 파일 다운로드 창 띄우기
        doc.save(`PathLearn_Report_${new Date().toISOString().slice(0,10)}.pdf`);
        
      } catch (error) {
        console.error("PDF 생성 중 에러 발생:", error);
        alert("PDF 생성 중 오류가 발생했습니다.");
      } finally {
        // 7. 버튼 상태 원상 복구
        pdfBtn.textContent = originalText;
        pdfBtn.disabled = false;
      }
    });
  }
});



