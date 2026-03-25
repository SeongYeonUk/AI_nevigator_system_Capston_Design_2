import { API_BASE_URL, CHAT_API_MODE } from "../config.js";

function buildMockAnswer(question, depth) {
  const snippets = [
    "좋은 질문입니다. 먼저 목표를 세부 단계로 나눈 뒤 우선순위를 정해 보세요.",
    "이전 맥락과 연결된 하위 주제로 분기하면 학습 효율이 높아집니다.",
    "다음 단계에서 검증 가능한 작은 작업 단위를 정의해 보세요.",
    "유사한 질문을 묶어 트리 단위로 비교하면 핵심이 더 명확해집니다."
  ];
  const idx = (question.length + depth) % snippets.length;
  return snippets[idx];
}

async function request(path, options = {}, token = "") {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {})
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  const contentType = response.headers.get("content-type") || "";
  const payload = contentType.includes("application/json")
    ? await response.json().catch(() => null)
    : await response.text().catch(() => "");

  if (!response.ok) {
    const message =
      (payload && typeof payload === "object" && payload.message) ||
      (payload &&
        typeof payload === "object" &&
        payload.error &&
        typeof payload.error === "string" &&
        payload.error) ||
      (payload &&
        typeof payload === "object" &&
        payload.error &&
        typeof payload.error.message === "string" &&
        payload.error.message) ||
      (typeof payload === "string" && payload) ||
      `채팅 API 호출에 실패했습니다. (HTTP ${response.status})`;
    throw new Error(message);
  }

  return payload;
}

export function getRoomsApi(token = "") {
  return request("/api/chat/rooms", { method: "GET" }, token);
}

export async function createRoomApi(title, token = "") {
  const trimmed = title.trim();
  const encoded = encodeURIComponent(trimmed);
  return request(`/api/chat/room?title=${encoded}`, { method: "POST" }, token);
}

export function updateRoomTitleApi(roomId, title, token = "") {
  const encoded = encodeURIComponent(String(title || "").trim());
  return request(`/api/chat/room/${roomId}/title?title=${encoded}`, { method: "PUT" }, token);
}

export function getRoomHistoryApi(roomId, token = "") {
  // 주소 뒤에 현재 시간(밀리초)을 붙여 매번 다른 URL처럼 보이게 만듭니다.
  return request(`/api/chat/room/${roomId}/history?t=${Date.now()}`, { method: "GET" }, token);
}

export function getRoomTreeApi(roomId, token = "") {
  return request(`/api/chat/room/${roomId}/tree?t=${Date.now()}`, { method: "GET" }, token);
}

export function askChatApi({ roomId,parentId, message, token = "" }) {
  return request(
    "/api/chat",
    {
      method: "POST",
      body: JSON.stringify({ roomId, parentId, message })
    },
    token
  );
}

export async function requestAssistantTurn({ roomId, message, parentId, token }) {
  return askChatApi({ roomId, parentId, message, token });
}

export function getNodeInsightApi(nodeId, token = "") { // 인사이트 API 추가
  return request(`/api/chat/node/${nodeId}/insight`, { method: "GET" }, token);
}

export function deleteRoomApi(roomId, token = "") {
  return request(`/api/chat/room/${roomId}`, { method: "DELETE" }, token);
}
